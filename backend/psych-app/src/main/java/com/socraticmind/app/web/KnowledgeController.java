package com.socraticmind.app.web;

import com.socraticmind.agent.dto.SearchResult;
import com.socraticmind.agent.knowledge.KnowledgeFlow;
import com.socraticmind.app.review.KnowledgeReviewStore;
import com.socraticmind.app.security.InternalAuthInterceptor;
import com.socraticmind.common.model.Chunk;
import com.socraticmind.common.model.Operator;
import com.socraticmind.common.port.OrgStore;
import com.socraticmind.common.model.KnowledgeDocument;
import com.socraticmind.common.model.RagAnswer;
import com.socraticmind.common.port.KnowledgeStore;
import com.socraticmind.common.support.DocumentVisibility;
import com.socraticmind.common.support.Hashes;
import com.socraticmind.knowledge.dto.IngestCommand;
import com.socraticmind.knowledge.dto.IngestResult;
import com.socraticmind.knowledge.dto.SyncResult;
import com.socraticmind.knowledge.service.DocumentSyncService;
import com.socraticmind.knowledge.service.IngestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import com.socraticmind.app.security.RequirePerm;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeFlow flow;
    private final IngestionService ingestion;
    private final DocumentSyncService sync;
    private final KnowledgeStore store;
    private final KnowledgeReviewStore reviewStore;
    private final OrgStore orgStore;
    private final boolean orgEnabled;
    private final String storeKind;

    public KnowledgeController(KnowledgeFlow flow, IngestionService ingestion, DocumentSyncService sync,
                               KnowledgeStore store, KnowledgeReviewStore reviewStore,
                               OrgStore orgStore,
                               @Value("${psych.org.enabled:false}") boolean orgEnabled,
                               @Value("${psych.store:memory}") String storeKind) {
        this.flow = flow;
        this.ingestion = ingestion;
        this.sync = sync;
        this.store = store;
        this.reviewStore = reviewStore;
        this.orgStore = orgStore;
        this.orgEnabled = orgEnabled;
        this.storeKind = storeKind;
    }

    /** scopes 不传 = 不限制范围；传了就只检索与这些范围相交的已发布文档。 */
    public record ChatRequest(@NotBlank String message, List<String> scopes) {
    }

    public record SearchRequest(@NotBlank String query, Integer topK, List<String> scopes) {
    }

    /**
     * 同步请求。externalKey 是外部系统的主键（工单号、仓库路径、源文档 id）。
     * 同一个 key 再来一次：内容没变什么都不做，变了就重建块并要求重新审核。
     */
    public record SyncRequest(
            @NotBlank String externalKey,
            @NotBlank String title,
            String source,
            String sourceUrl,
            String collection,
            @Size(max = 200_000) String content,
            Boolean publish
    ) {
    }

    public record AclRequest(List<String> scopes) {
    }

    /**
     * publish 的语义是"提交审核"，不是"发布"。
     * 入库一定落在 DRAFT / REVIEWING，只有 {@code /review} 的 approve 才能让它成为可引用的证据。
     */
    public record UploadRequest(
            @NotBlank String title,
            String source,
            String sourceUrl,
            String collection,
            @Size(max = 200_000) String content,
            Boolean publish
    ) {
    }

    public record ReviewRequest(@NotBlank String decision, String reviewer, String note) {
    }

    /** 管理端只读视图：不暴露向量，避免把调参噪音带到页面。 */
    public record ChunkView(
            String id,
            String parentId,
            int index,
            String titlePath,
            String sectionPath,
            String content,
            boolean parent,
            Integer pageStart,
            Integer pageEnd,
            String language
    ) {
        static ChunkView of(Chunk c) {
            return new ChunkView(c.id(), c.parentId(), c.index(), c.titlePath(), c.sectionPath(),
                    c.content(), c.parent(), c.pageStart(), c.pageEnd(), c.language());
        }
    }

    @PostMapping("/chat")
    public RagAnswer chat(@Valid @RequestBody ChatRequest request,
                          @RequestAttribute(name = InternalAuthInterceptor.OPERATOR_ATTR, required = false)
                          Operator operator) {
        return flow.run(request.message(), scopesFor(request.scopes(), operator));
    }

    @PostMapping("/search")
    public SearchResult search(@Valid @RequestBody SearchRequest request,
                               @RequestAttribute(name = InternalAuthInterceptor.OPERATOR_ATTR, required = false)
                               Operator operator) {
        return flow.search(request.query(), request.topK() == null ? 5 : request.topK(),
                scopesFor(request.scopes(), operator));
    }

    /**
     * 有身份时用身份的 scopes；组织模式下没有身份时，只能看公开资料。
     *
     * 最后一句是关键：null 表示"不过滤"，空集合表示"只看无范围限制的"。
     * 如果匿名请求拿到 null，那么给文档设了 ACL 之后，前台仍然搜得到——
     * 看起来像权限没生效，实际是这里放行了全部。
     *
     * 组织模式关闭时（单机开发）仍允许显式传 scopes，那是调试开关，不是权限。
     */
    private Set<String> scopesFor(List<String> requested, Operator operator) {
        if (operator != null) return operator.scopes();
        if (orgEnabled) return Set.of();
        return requested == null || requested.isEmpty() ? null : Set.copyOf(requested);
    }

    @PostMapping("/documents/sync")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public ResponseEntity<SyncResult> sync(@Valid @RequestBody SyncRequest request) {
        if (request.content() == null || request.content().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        IngestCommand cmd = new IngestCommand(
                request.collection() == null ? "default" : request.collection(),
                request.title(), request.source(), request.sourceUrl(), "zh",
                Boolean.TRUE.equals(request.publish()), request.content());
        return ResponseEntity.ok(sync.sync(request.externalKey(), cmd));
    }

    @DeleteMapping("/documents/{id}")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        if (store.findDocument(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        store.markDeleted(id, Instant.now());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", id);
        out.put("status", KnowledgeDocument.ARCHIVED);
        out.put("note", "软删除：已从检索范围摘除，块保留，可用同步接口重新入库");
        return ResponseEntity.ok(out);
    }

    /**
     * 设置语料可见范围。空列表 = 公开；组织模式下只能写本租户的团队 id。
     *
     * 校验不是"防手滑"，而是让 ACL 从自由字符串变成引用：
     * 写错一个字母的 team id，在字符串世界里的效果是"谁都看不见"或"谁都看得见"，
     * 而且没有任何地方会报错。
     */
    @PutMapping("/documents/{id}/acl")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public ResponseEntity<Map<String, Object>> acl(@PathVariable String id,
                                                   @RequestBody AclRequest request,
                                                   @RequestAttribute(name = InternalAuthInterceptor.OPERATOR_ATTR, required = false)
                                                   Operator operator) {
        if (store.findDocument(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<String> scopes = request.scopes() == null ? List.of() : request.scopes();
        String invalid = operator == null ? null : invalidScope(scopes, operator);
        if (invalid != null) {
            return ResponseEntity.badRequest().body(Map.of("error", invalid));
        }
        String normalized = DocumentVisibility.normalizeAcl(String.join(",", scopes));
        store.updateAcl(id, normalized);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", id);
        out.put("aclScope", normalized == null ? "" : normalized);
        return ResponseEntity.ok(out);
    }

    /** 返回第一个不合法的 scope 的说明；全部合法时返回 null。 */
    private String invalidScope(List<String> scopes, Operator operator) {
        for (String raw : scopes) {
            String scope = raw == null ? "" : raw.trim();
            if (scope.isEmpty()) continue;
            if (scope.startsWith("tenant:")) {
                if (!scope.substring("tenant:".length()).equals(operator.tenantId())) {
                    return "只能引用自己所属的租户：" + operator.tenantId();
                }
                continue;
            }
            if (scope.startsWith("team:")) {
                String teamId = scope.substring("team:".length());
                if (!orgStore.teamExists(operator.tenantId(), teamId)) {
                    return "团队不存在或不属于本租户：" + teamId;
                }
                continue;
            }
            return "可见范围必须是 tenant:<id> 或 team:<id>，收到：" + scope;
        }
        return null;
    }

    @PostMapping("/documents")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public ResponseEntity<IngestResult> upload(@Valid @RequestBody UploadRequest request) {
        if (request.content() == null || request.content().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        IngestCommand cmd = new IngestCommand(
                request.collection() == null ? "default" : request.collection(),
                request.title(), request.source(), request.sourceUrl(), "zh",
                Boolean.TRUE.equals(request.publish()), request.content());
        return ResponseEntity.ok(ingestion.ingest(cmd));
    }

    /**
     * 替换一篇已有资料的内容：文档 id、可见范围保持不变，版本 +1。
     *
     * 必须走"同文档 id 写新版本"这条路：如果先删再建，旧链接、审核记录和 ACL 全部断掉，
     * 而检索在中间窗口里还会读到旧块。状态一律回到 REVIEWING——内容变了，旧签字不能继承。
     */
    @PostMapping("/documents/{id}/replace")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public ResponseEntity<IngestResult> replace(@PathVariable String id,
                                                @Valid @RequestBody UploadRequest request) {
        KnowledgeDocument doc = store.findDocument(id).orElse(null);
        if (doc == null) {
            return ResponseEntity.notFound().build();
        }
        if (request.content() == null || request.content().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        IngestCommand cmd = new IngestCommand(
                request.collection() == null || request.collection().isBlank()
                        ? (doc.collection() == null ? "default" : doc.collection())
                        : request.collection(),
                request.title(), request.source(), request.sourceUrl(), "zh", true, request.content());
        // 先摘旧块再写新版本，避免新旧块同时留在索引里
        store.deleteChunksOf(id);
        IngestResult result = ingestion.ingestAs(cmd, id, doc.externalKey(),
                Hashes.sha256Hex(request.content()), KnowledgeDocument.REVIEWING);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/documents/{id}/review")
    @RequirePerm(Operator.Perm.CONTENT_REVIEW)
    public ResponseEntity<Map<String, Object>> review(@PathVariable String id,
                                                      @Valid @RequestBody ReviewRequest request,
                                                      @RequestAttribute(name = InternalAuthInterceptor.OPERATOR_ATTR, required = false)
                                                      Operator operator) {
        KnowledgeDocument doc = store.findDocument(id).orElse(null);
        if (doc == null) {
            return ResponseEntity.notFound().build();
        }
        // 组织模式下署名只能来自令牌。拦截器已经挡了一层，这里再挡一次：
        // 直接调用控制器（测试、内部脚本）绕过了拦截器，不能因此产生匿名签字。
        if (orgEnabled && operator == null) {
            return ResponseEntity.status(401).body(Map.of("error", "组织模式下必须带操作者令牌"));
        }
        String decision = request.decision().toLowerCase();
        String status;
        String normalized;
        switch (decision) {
            case "approve", "publish" -> {
                status = KnowledgeDocument.PUBLISHED;
                normalized = "approve";
            }
            case "reject", "archive" -> {
                status = KnowledgeDocument.ARCHIVED;
                normalized = "reject";
            }
            case "review" -> {
                status = KnowledgeDocument.REVIEWING;
                normalized = "review";
            }
            default -> {
                return ResponseEntity.badRequest().body(Map.of("error", "decision 必须是 approve / reject / review"));
            }
        }
        store.updateDocumentStatus(id, status);

        // 状态变更本身不构成一次审核：没有"谁、什么时候、哪一版"的状态流转，
        // 事后无法证明"改版即重审"真的发生过。记录与状态一起写，二者不可分。
        String operatorId = operator == null ? null : operator.id();
        String operatorName = operator == null ? request.reviewer() : operator.display();
        var review = reviewStore.save(id, doc.title(), doc.version(), normalized,
                operatorId, operatorName, request.note(), doc.aclScope());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", id);
        out.put("status", status);
        out.put("review", review);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/documents")
    public List<KnowledgeDocument> documents() {
        return store.findDocuments();
    }

    /** 资料详情里的切分预览：含父块，按章节分组渲染。 */
    @GetMapping("/documents/{id}/chunks")
    public ResponseEntity<List<ChunkView>> chunks(@PathVariable String id) {
        if (store.findDocument(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(store.listChunks(id).stream().map(ChunkView::of).toList());
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<KnowledgeDocument> docs = store.findDocuments();
        out.put("documents", docs.size());
        out.put("published", docs.stream().filter(d -> KnowledgeDocument.PUBLISHED.equals(d.status())).count());
        out.put("reviewing", docs.stream().filter(d -> KnowledgeDocument.REVIEWING.equals(d.status())).count());
        out.put("chunks", store.countChunks());
        out.put("store", storeKind);
        return out;
    }
}
