package com.socraticmind.knowledge.metadata;

import com.socraticmind.common.model.Chunk;
import com.socraticmind.common.model.EvidenceItem;
import com.socraticmind.common.support.Ids;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 证据属性抽取。
 *
 * 只做"从原文里找"，不做"推测"。推荐强度、证据质量一律 null——这两个字段必须由审核人填，
 * 让程序或模型猜等于制造假证据（见 docs/01-方案/04-RAG知识数据架构.md §4.2）。
 */
public class EvidenceExtractor {

    private static final Pattern POPULATION =
            Pattern.compile("(成人|青少年|儿童|老年|孕妇|产后|学生|大学生|职场|患者)");
    private static final String[] LIMITATION_HINTS = {"但", "限制", "除外", "不宜", "注意", "并非", "不能替代"};

    public EvidenceItem extract(Chunk chunk, String reviewStatus) {
        String content = chunk.content();
        List<String> sentences = split(content);

        String type = classify(content);
        String condition = sentences.stream()
                .filter(s -> s.contains("适用于") || s.contains("适用范围") || s.contains("对于"))
                .findFirst().map(s -> trim(s, 160)).orElse(null);

        String population = null;
        Matcher m = POPULATION.matcher(content);
        if (m.find()) population = m.group(1);

        List<String> limitations = new ArrayList<>();
        for (String s : sentences) {
            for (String hint : LIMITATION_HINTS) {
                if (s.contains(hint) && limitations.size() < 3) {
                    limitations.add(trim(s, 160));
                    break;
                }
            }
        }

        return new EvidenceItem(Ids.of("ev"), chunk.id(), type, condition, population,
                null, null, null, limitations, reviewStatus);
    }

    private static String classify(String content) {
        if (content.contains("不推荐") || content.contains("不建议")) return "recommendation_against";
        if (content.contains("推荐") || content.contains("建议")) return "recommendation";
        if (content.contains("适用于") || content.contains("适用范围")) return "scope";
        if (content.contains("定义") || content.contains("是指") || content.contains("指的是")) return "definition";
        return "descriptive_text";
    }

    private static List<String> split(String content) {
        List<String> out = new ArrayList<>();
        for (String s : content.split("[。！？；\\n]")) {
            String t = s.trim();
            if (t.length() >= 4) out.add(t);
        }
        return out;
    }

    private static String trim(String s, int max) {
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
