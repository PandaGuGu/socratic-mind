package com.socraticmind.rag.query;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 问题分类：规则优先。第一版不调用模型——分类错误最多影响检索权重，不值得一次模型往返的延迟。
 */
@Component
public class QueryClassifier {

    private static final Map<String, String[]> RULES = new LinkedHashMap<>();

    static {
        RULES.put("cbt_technique", new String[]{"自动思维", "核心信念", "认知重构", "行为实验", "苏格拉底", "记录表", "暴露", "作业"});
        RULES.put("scale", new String[]{"量表", "评分", "PHQ", "GAD", "得分", "几分"});
        RULES.put("scope", new String[]{"适用", "适合", "什么人", "能不能用于", "禁忌", "不适合"});
        RULES.put("theory", new String[]{"理论", "模型", "原理", "机制", "为什么", "定义", "是什么", "区别"});
        RULES.put("popular", new String[]{"怎么办", "如何缓解", "怎么调整", "有什么方法", "小技巧"});
    }

    public String classify(String question) {
        if (question == null) return "popular";
        for (Map.Entry<String, String[]> e : RULES.entrySet()) {
            for (String kw : e.getValue()) {
                if (question.contains(kw)) return e.getKey();
            }
        }
        return "popular";
    }
}
