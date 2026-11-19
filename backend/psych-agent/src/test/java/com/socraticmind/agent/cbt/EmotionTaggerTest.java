package com.socraticmind.agent.cbt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 情绪标签的边界：只认用户自己写下的词，不推断、不打分。
 */
class EmotionTaggerTest {

    @Test
    void tagsExplicitWordsOnly() {
        List<String> tags = EmotionTagger.tag("一想到汇报就心慌，晚上也睡不着，特别焦虑");
        assertTrue(tags.contains("焦虑"));
        assertEquals(1, tags.size(), "同一类只打一个标签：" + tags);
    }

    @Test
    void multipleCategoriesAreKept() {
        List<String> tags = EmotionTagger.tag("又生气又委屈，觉得自己很没用");
        assertTrue(tags.contains("愤怒"));
        assertTrue(tags.contains("悲伤"));
    }

    @Test
    void doesNotInventEmotionFromNothing() {
        assertEquals(List.of(), EmotionTagger.tag("明天上午十点开会"));
        assertEquals(List.of(), EmotionTagger.tag(null));
    }
}
