package com.socraticmind.app.review;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 内存实现：单机开发与测试用。语义与 JDBC 档一致——尤其是"同版本覆盖、跨版本累积"。 */
@Component
@ConditionalOnProperty(name = "psych.review.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryKnowledgeReviewStore implements KnowledgeReviewStore {

    private static final Set<String> DECISIONS = Set.of("approve", "reject", "review");
    private static final int MAX_NAME = 64;
    private static final int MAX_NOTE = 1000;

    private final Map<String, DocReview> current = new ConcurrentHashMap<>();

    @Override
    public List<DocReview> all() {
        return current.values().stream()
                .sorted(Comparator.comparing(DocReview::reviewedAt).reversed())
                .toList();
    }

    @Override
    public List<DocReview> forDocument(String documentId) {
        return current.values().stream()
                .filter(r -> r.documentId().equals(documentId))
                .sorted(Comparator.comparing(DocReview::reviewedAt).reversed())
                .toList();
    }

    @Override
    public DocReview find(String documentId, String version) {
        return current.get(documentId + "@" + version);
    }

    @Override
    public DocReview save(String documentId, String title, String version, String decision,
                          String operatorId, String operatorName, String note, String aclSnapshot) {
        validate(documentId, version, decision, operatorName, note);
        DocReview review = new DocReview(documentId, title, version, decision, operatorId,
                operatorName == null || operatorName.isBlank() ? null : operatorName.strip(),
                Instant.now().toString(), note == null || note.isBlank() ? null : note.strip(),
                aclSnapshot);
        current.put(documentId + "@" + version, review);
        return review;
    }

    @Override
    public void clear() {
        current.clear();
    }

    static void validate(String documentId, String version, String decision, String operatorName, String note) {
        if (documentId == null || documentId.isBlank()) throw new IllegalArgumentException("documentId 不能为空");
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version 不能为空：审核针对版本，不针对文档");
        }
        if (decision == null || !DECISIONS.contains(decision)) {
            throw new IllegalArgumentException("decision 只能是 approve / reject / review");
        }
        if (operatorName != null && operatorName.length() > MAX_NAME) {
            throw new IllegalArgumentException("审核人名字过长");
        }
        if (note != null && note.length() > MAX_NOTE) {
            throw new IllegalArgumentException("备注最多 " + MAX_NOTE + " 字");
        }
    }
}
