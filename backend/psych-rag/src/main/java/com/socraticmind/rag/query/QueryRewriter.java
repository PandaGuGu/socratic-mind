package com.socraticmind.rag.query;

import com.socraticmind.common.support.TextUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 查询改写：同义词与术语补全。
 * 第一版是规则表——它可回归、零延迟、可解释；模型改写留到检索质量成为瓶颈时再加。
 */
@Component
public class QueryRewriter {

    private static final Map<String, String> TERMS = new LinkedHashMap<>();

    static {
        TERMS.put("CBT", "认知行为疗法");
        TERMS.put("认知行为", "认知行为疗法 认知模型");
        TERMS.put("自动思维", "负性自动思维 认知模型");
        TERMS.put("核心信念", "中间信念 核心信念 认知层次");
        TERMS.put("睡不着", "失眠 睡眠");
        TERMS.put("焦虑", "焦虑 紧张 担忧");
        TERMS.put("社恐", "社交焦虑");
        TERMS.put("拖延", "拖延 回避 行为激活");
        TERMS.put("自我批评", "自我批评 自我评价 核心信念");
    }

    public String rewrite(String question) {
        if (question == null || question.isBlank()) return "";
        StringBuilder sb = new StringBuilder(question.trim());
        for (Map.Entry<String, String> e : TERMS.entrySet()) {
            if (question.contains(e.getKey())) {
                sb.append(' ').append(e.getValue());
            }
        }
        if (!TextUtils.hasCjk(question)) {
            sb.append(' ').append(question);
        }
        return sb.toString().trim();
    }
}
