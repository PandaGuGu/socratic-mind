package com.socraticmind.agent.knowledge;

import com.socraticmind.agent.dto.SearchHit;
import com.socraticmind.agent.dto.SearchResult;
import com.socraticmind.common.model.Citation;
import com.socraticmind.common.model.EvidenceItem;
import com.socraticmind.common.model.KnowledgeDocument;
import com.socraticmind.common.model.RagAnswer;
import com.socraticmind.common.model.Verification;
import com.socraticmind.common.port.KnowledgeStore;
import com.socraticmind.common.support.TextUtils;
import com.socraticmind.rag.dto.EvidencePack;
import com.socraticmind.rag.dto.Hit;
import com.socraticmind.rag.dto.PackItem;
import com.socraticmind.rag.query.QueryClassifier;
import com.socraticmind.rag.query.QueryRewriter;
import com.socraticmind.rag.service.RagService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * KnowledgeGraph 的流程实现（7 个节点，顺序固定）：
 * 分类 → 改写 → 检索 → 证据评估 → 生成 → 引用校验 → 格式化。
 *
 * 为什么先用朴素编排而不是框架 Graph：M1–M2 的验收点是"证据能不能用"，流程只有一条直线，
 * 上框架不增加任何能力。等 M4 引入中断与 Checkpoint 时再替换（ADR-0002 备注）。
 */
@Service
public class KnowledgeFlow {

    private static final int TOP_K = 4;

    private final QueryClassifier classifier;
    private final QueryRewriter rewriter;
    private final RagService rag;
    private final KnowledgeStore store;

    public KnowledgeFlow(QueryClassifier classifier, QueryRewriter rewriter,
                         RagService rag, KnowledgeStore store) {
        this.classifier = classifier;
        this.rewriter = rewriter;
        this.rag = rag;
        this.store = store;
    }

    public RagAnswer run(String question) {
        List<String> trace = new ArrayList<>();

        String type = classifier.classify(question);
        trace.add("classify_query → " + type);

        String rewritten = rewriter.rewrite(question);
        trace.add("rewrite_query → " + TextUtils.truncate(rewritten, 40));

        List<Hit> hits = rag.retrieve(rewritten, TOP_K);
        trace.add("retrieve_evidence → " + hits.size() + " hits");

        EvidencePack pack = rag.assemble(rewritten, hits);
        trace.add("evaluate_evidence → sufficient=" + pack.evidenceSufficient() + " (" + pack.reason() + ")");

        if (!pack.evidenceSufficient()) {
            trace.add("branch → insufficient");
            String answer = insufficientAnswer(pack);
            trace.add("format_response");
            return new RagAnswer(question, RagAnswer.INSUFFICIENT, answer, List.of(),
                    evidenceOf(pack), Verification.empty(), rag.modelId(), trace);
        }

        String answer = rag.generate(question, pack);
        trace.add("generate_answer → " + answer.length() + " chars, by " + rag.modelId());

        // 模型自己说"资料不足"时，状态必须跟着变：不能一边说没资料，一边把状态记成 answered。
        // 这是确定性后检，不依赖模型遵守提示词；真实模型在正式集上确实出现过这种情况。
        if (selfRefused(answer)) {
            trace.add("branch → model_self_refusal");
            trace.add("format_response");
            return new RagAnswer(question, RagAnswer.INSUFFICIENT, answer, List.of(),
                    evidenceOf(pack), Verification.empty(), rag.modelId(), trace);
        }

        Verification verification = rag.verify(answer, pack);
        trace.add("verify_citations → valid=" + verification.allCitationsValid()
                + " coverage=" + verification.citationCoverage());

        trace.add("format_response");
        java.util.Set<Integer> used = rag.referenced(answer);
        List<Citation> citations = used.isEmpty()
                ? citationsOf(pack)
                : pack.items().stream()
                    .filter(item -> used.contains(item.citation().number()))
                    .map(PackItem::citation)
                    .toList();
        return new RagAnswer(question, RagAnswer.ANSWERED, answer, citations,
                evidenceOf(pack), verification, rag.modelId(), trace);
    }

    public SearchResult search(String question, int topK) {
        String type = classifier.classify(question);
        String rewritten = rewriter.rewrite(question);
        List<Hit> hits = rag.retrieve(rewritten, Math.max(1, Math.min(topK, 20)));

        List<SearchHit> out = new ArrayList<>();
        for (Hit h : hits) {
            Optional<KnowledgeDocument> doc = store.findDocument(h.chunk().documentId());
            out.add(new SearchHit(h.chunk().id(), h.chunk().documentId(),
                    doc.map(KnowledgeDocument::title).orElse("-"),
                    h.chunk().sectionPath(), Math.round(h.score() * 10000) / 10000.0,
                    Math.round(h.vectorScore() * 10000) / 10000.0,
                    Math.round(h.textScore() * 10000) / 10000.0,
                    h.via(), TextUtils.truncate(h.chunk().content(), 60)));
        }
        return new SearchResult(question, type, rewritten, out);
    }

    /**
     * 只看**开头**：模型常在末尾补一句"以上仅供参考"，那不算拒答。
     * 也不能全文搜"资料不足"——可答题里可能正好在描述"系统在资料不足时会拒答"，
     * 全文匹配会把这种正常回答误判成拒答（正式集上实际发生过）。
     */
    static boolean selfRefused(String answer) {
        if (answer == null) return false;
        String head = answer.strip().replaceFirst("^[\\s\\p{Punct}\u201c\u201d\u2018\u2019]+", "");
        for (String marker : List.of("现有资料不足", "资料不足", "不足以回答", "无法回答",
                "提供的证据未提及", "未提及", "没有相关信息")) {
            if (head.startsWith(marker)) return true;
        }
        return false;
    }

    private String insufficientAnswer(EvidencePack pack) {
        StringBuilder sb = new StringBuilder("现有资料不足以回答这一点。\n\n");
        sb.append("原因：").append(pack.reason()).append("。\n");
        if (!pack.items().isEmpty()) {
            sb.append("最接近的内容是这份资料的相关段落，但它不能直接支持结论：\n");
            PackItem first = pack.items().get(0);
            sb.append("· ").append(first.citation().display()).append("\n");
        }
        sb.append("\n可以换个问法，或到知识库里补一份对应资料。");
        return sb.toString();
    }

    private List<Citation> citationsOf(EvidencePack pack) {
        return pack.items().stream().map(PackItem::citation).toList();
    }

    private List<EvidenceItem> evidenceOf(EvidencePack pack) {
        return pack.items().stream().map(PackItem::evidence).toList();
    }
}
