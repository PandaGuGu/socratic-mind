package com.socraticmind.app.guard;

import com.socraticmind.session.CbtSession;
import com.socraticmind.session.CrisisCard;
import com.socraticmind.session.MoodPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 危机卡片与情绪自评的边界。
 *
 * 两条不许越线：
 * 1. 卡片里不能复述风险词，也不能写"你有风险"——用户可能要把它转发给别人；
 * 2. 自评分数只能是 0–10，且只有用户点击能产生，服务端不接受越界值。
 */
class CrisisCardTest {

    @Test
    void cardKeepsHotlinesAndContactButNotRiskWords() {
        CbtSession s = new CbtSession("cbt_1", "thread_1", "u1");
        s.setContact("小周", "13800001111", "室友");
        s.setSafetyStatus("hold");
        s.setSafetyReason("命中安全规则：self_harm，已暂停并给出求助渠道");

        CrisisCard card = CrisisCard.of(s, s.safetyReason());

        assertTrue(card.hotlines().stream().anyMatch(h -> h.contains("12356")));
        assertTrue(card.hotlines().stream().anyMatch(h -> h.contains("120")));
        assertEquals("小周", card.contactName());
        assertEquals("13800001111", card.contactPhone());
        assertTrue(card.notice().contains("联系我"), "文案要能被用户直接转发");
        assertFalse(card.notice().contains("自杀"), "卡片不复述风险词");
        assertFalse(card.notice().contains("self_harm"), "规则 id 不进用户文案");
        assertFalse(card.acknowledged());
    }

    @Test
    void ackIsRecordedOnce() {
        CbtSession s = new CbtSession("cbt_2", "thread_2", "u1");
        s.ackSafety("console");
        assertTrue(CrisisCard.of(s, null).acknowledged());
        assertEquals("console", s.safetyAckBy());
    }

    @Test
    void moodIsUserScoredOnly() {
        CbtSession s = new CbtSession("cbt_3", "thread_3", "u1");
        s.addMood(7, 1);
        s.addMood(4, 3);
        assertEquals(2, s.moodPoints().size());
        assertEquals(4, s.moodPoints().get(1).score());
        assertThrows(IllegalArgumentException.class, () -> MoodPoint.of(1, 11));
        assertThrows(IllegalArgumentException.class, () -> MoodPoint.of(1, -1));
    }

    @Test
    void contactIsOptionalAndBlankSafe() {
        CbtSession s = new CbtSession("cbt_4", "thread_4", "u1");
        assertFalse(s.hasContact());
        s.setContact("  ", null, "");
        assertFalse(s.hasContact());
        s.setContact("小林", null, null);
        assertTrue(s.hasContact());
        assertNull(s.contactPhone());
    }
}
