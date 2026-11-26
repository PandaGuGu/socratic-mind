package com.socraticmind.common.support;

import com.socraticmind.common.model.KnowledgeDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 可见性判定：一条文档"能不能被检索到"。
 *
 * 三件事按顺序否掉：没发布、已删除、不在调用方的范围内。
 * 放在 common 而不是各存储实现里，是为了让内存档与 PG 档不可能走出两套语义——
 * 这类"两边都实现一遍"的规则，最终一定会分叉。
 */
public final class DocumentVisibility {

    private DocumentVisibility() {
    }

    /**
     * @param scopes 调用方持有的范围集合；null 表示不限制（只按发布状态过滤）
     */
    public static boolean visible(KnowledgeDocument doc, Set<String> scopes) {
        if (doc == null) return false;
        if (!KnowledgeDocument.PUBLISHED.equals(doc.status())) return false;
        if (doc.deleted()) return false;
        if (scopes == null) return true;

        String acl = doc.aclScope();
        if (acl == null || acl.isBlank()) return true;
        for (String part : acl.split("[,;\\s]+")) {
            String scope = part.trim();
            if (!scope.isEmpty() && scopes.contains(scope)) return true;
        }
        return false;
    }

    public static List<String> visibleIds(List<KnowledgeDocument> docs, Set<String> scopes) {
        List<String> ids = new ArrayList<>();
        for (KnowledgeDocument doc : docs) {
            if (visible(doc, scopes)) ids.add(doc.id());
        }
        return ids;
    }

    /** 规范化 ACL 输入：去重、去空白、统一逗号分隔。空串表示公开。 */
    public static String normalizeAcl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (String part : raw.split("[,;\\s]+")) {
            String scope = part.trim();
            if (!scope.isEmpty()) seen.add(scope);
        }
        return seen.isEmpty() ? null : String.join(",", seen);
    }
}
