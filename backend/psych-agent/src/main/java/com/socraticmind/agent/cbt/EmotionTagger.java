package com.socraticmind.agent.cbt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从用户原话里标出"他说了哪些情绪词"。
 *
 * 这不是情绪识别，更不是量表：它只做一件事——把用户**自己写下的**情绪词
 * 归到几个固定标签上，让记录里至少有一个可统计的维度。
 * 旧版本的 emotions 字段是全空的，M6 评估因此没有任何可用变量。
 *
 * 明确不做的事：不推断未写出的情绪、不做强度打分、不区分"我很焦虑"与"我不焦虑"。
 * 标签只说明"这句话里出现了这类词"，结论必须回原文核对。
 */
public final class EmotionTagger {

    private static final Map<String, List<String>> LEXICON = build();

    private static Map<String, List<String>> build() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("焦虑", List.of("焦虑", "紧张", "心慌", "手心出汗", "坐立不安", "担心", "忐忑", "慌"));
        m.put("恐惧", List.of("害怕", "恐惧", "怕", "吓人", "不敢"));
        m.put("悲伤", List.of("难过", "低落", "想哭", "委屈", "失落", "沮丧", "没劲"));
        m.put("愤怒", List.of("生气", "愤怒", "火大", "恼火", "烦死", "憋屈"));
        m.put("羞耻", List.of("丢人", "羞愧", "难堪", "尴尬", "没脸"));
        m.put("内疚", List.of("内疚", "自责", "对不起", "愧疚", "怪自己"));
        m.put("疲惫", List.of("累", "疲惫", "撑不住", "没力气", "精疲力尽"));
        return Map.copyOf(m);
    }

    private EmotionTagger() {
    }

    /** 返回命中的标签（去重、稳定顺序），没有命中返回空列表。 */
    public static List<String> tag(String text) {
        if (text == null || text.isBlank()) return List.of();
        Set<String> found = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : LEXICON.entrySet()) {
            for (String word : entry.getValue()) {
                if (text.contains(word)) {
                    found.add(entry.getKey());
                    break;
                }
            }
        }
        return new ArrayList<>(found);
    }
}
