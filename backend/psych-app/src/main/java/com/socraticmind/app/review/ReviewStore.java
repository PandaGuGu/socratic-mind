package com.socraticmind.app.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 审核记录：谁在什么时候看过哪一版，结论是什么。
 *
 * 与旧版最大的不同：**只追加、不覆盖**。旧实现每个对象只保留最后一次签字，
 * 重新登记会抹掉上一次；临床审核需要的恰恰是"这一版是谁签的、上一版谁签的"。
 *
 * 记录对象分两类：
 * - 全局配置（safety / lexicon / policy / limits）：target 为空；
 * - 有实例的对象（flow 画布 / session 会话）：target 是画布 id 或会话 id。
 *
 * 存法仍是"文件即真值"，但结构换成 { entries: [...] }；读取时兼容旧的 kind → Review 映射。
 */
@Component
public class ReviewStore {

    private static final Logger log = LoggerFactory.getLogger(ReviewStore.class);

    private static final Set<String> KINDS = Set.of("safety", "lexicon", "flow", "policy", "limits", "session");
    private static final Set<String> CONFIG_STATUSES = Set.of("approved", "changes_requested");
    private static final Set<String> SESSION_STATUSES = Set.of("ok", "watch", "refer", "urgent");
    private static final Set<String> ALL_STATUSES = Set.of(
            "approved", "changes_requested", "ok", "watch", "refer", "urgent");

    public record Review(String kind, String target, int version, String status, String reviewer,
                         String operatorId, String reviewedAt, String note,
                         List<String> checks, boolean reported) {

        public Review {
            kind = kind == null ? "" : kind.trim();
            target = target == null ? "" : target.trim();
            status = status == null ? "" : status.trim();
            reviewer = reviewer == null ? "" : reviewer.trim();
            operatorId = operatorId == null ? "" : operatorId.trim();
            note = note == null ? "" : note.trim();
            checks = checks == null ? List.of() : checks.stream()
                    .filter(c -> c != null && !c.isBlank()).map(String::trim).toList();
        }
    }

    private record FileData(List<Review> entries) {
    }

    private final Path path;
    private final ObjectMapper mapper;
    private volatile List<Review> entries = List.of();

    public ReviewStore(@Value("${psych.review.file:config/reviews.json}") String file,
                       ObjectMapper mapper) {
        this.path = Path.of(file).toAbsolutePath().normalize();
        this.mapper = mapper;
        load();
    }

    public Path path() {
        return path;
    }

    /** 每个对象的最新一条（按 kind 取；不区分 target，仅给旧调用兜底）。 */
    public Map<String, Review> all() {
        Map<String, Review> out = new LinkedHashMap<>();
        for (Review r : entries) out.put(r.kind(), r);
        return out;
    }

    public Review find(String kind) {
        return find(kind, "");
    }

    public Review find(String kind, String target) {
        String t = target == null ? "" : target.trim();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Review r = entries.get(i);
            if (r.kind().equals(kind) && r.target().equals(t)) return r;
        }
        return null;
    }

    /** 某个对象的历史，新→旧。 */
    public List<Review> history(String kind, String target) {
        String t = target == null ? "" : target.trim();
        List<Review> out = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Review r = entries.get(i);
            if (r.kind().equals(kind) && r.target().equals(t)) out.add(r);
        }
        return out;
    }

    public List<Review> historyAll() {
        List<Review> out = new ArrayList<>(entries);
        java.util.Collections.reverse(out);
        return out;
    }

    public int count(String kind, String target) {
        return history(kind, target).size();
    }

    public synchronized Review save(String kind, int version, String status, String reviewer, String note) {
        return save(kind, "", version, status, reviewer, "", note, List.of(), false);
    }

    /**
     * 追加一条签字。
     *
     * version 由调用方传入"当前生效版本"；reported 只对 session 有意义，
     * 表示"我已经把这条上报/联系出去了"——系统没有自动通知通道，这里只负责留痕。
     */
    public synchronized Review save(String kind, String target, int version, String status,
                                    String reviewer, String operatorId, String note,
                                    List<String> checks, boolean reported) {
        if (kind == null || !KINDS.contains(kind)) {
            throw new IllegalArgumentException("kind 只能是 " + String.join(" / ", KINDS));
        }
        if (status == null || !ALL_STATUSES.contains(status)) {
            throw new IllegalArgumentException("不支持的审核结论：" + status);
        }
        if ("session".equals(kind)) {
            if (!SESSION_STATUSES.contains(status)) {
                throw new IllegalArgumentException("会话复核结论只能是 ok / watch / refer / urgent");
            }
        } else if (!CONFIG_STATUSES.contains(status)) {
            throw new IllegalArgumentException("配置审核结论只能是 approved / changes_requested");
        }
        String t = target == null ? "" : target.trim();
        if (("session".equals(kind) || "flow".equals(kind)) && t.isEmpty()) {
            throw new IllegalArgumentException(kind + " 审核必须带 target（" + ("session".equals(kind) ? "会话 id" : "画布 id") + "）");
        }
        if (reviewer == null || reviewer.isBlank() || reviewer.strip().length() > 32) {
            throw new IllegalArgumentException("审核人必填，最多 32 字");
        }
        if (note != null && note.length() > 1000) {
            throw new IllegalArgumentException("备注最多 1000 字");
        }
        if (checks != null && checks.size() > 30) {
            throw new IllegalArgumentException("勾选项过多");
        }
        if (version < 0) {
            throw new IllegalArgumentException("版本号不能为负");
        }
        Review review = new Review(kind, t, version, status, reviewer.strip(),
                operatorId, Instant.now().toString(), note, checks, "session".equals(kind) && reported);
        List<Review> next = new ArrayList<>(entries);
        next.add(review);
        write(next);
        entries = List.copyOf(next);
        log.info("审核记录已保存：{} {} v{} {} by {}", kind, t, version, status, review.reviewer());
        return review;
    }

    private void load() {
        if (!Files.exists(path)) return;
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            JsonNode root = mapper.readTree(json);
            List<Review> loaded = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode n : root) loaded.add(mapper.treeToValue(n, Review.class));
            } else if (root.has("entries")) {
                for (JsonNode n : root.path("entries")) loaded.add(mapper.treeToValue(n, Review.class));
            } else {
                // 旧格式：{ "safety": {...}, "lexicon": {...} }
                var fields = root.fields();
                while (fields.hasNext()) {
                    loaded.add(mapper.treeToValue(fields.next().getValue(), Review.class));
                }
            }
            entries = List.copyOf(loaded);
        } catch (Exception e) {
            // 坏文件不生效：保持空表（= 全部未审核），比"看起来审过了"安全。
            log.error("审核记录文件不可用，按未审核处理：{}", e.getMessage());
        }
    }

    private void write(List<Review> reviews) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), new FileData(reviews));
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("审核记录写入失败：" + e.getMessage(), e);
        }
    }
}
