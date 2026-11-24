package com.socraticmind.infrastructure.store;

import com.socraticmind.session.CbtSession;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 翻页游标的边界：同一时刻创建的两轮也必须都能翻到。
 *
 * 只按时间戳翻页会漏掉"同一毫秒里的第二条"——这种漏数据最难发现，
 * 因为页面看起来一切正常。这里把两条会话的时间设成完全相同，验证 (createdAt, id) 的第二个字段真的生效。
 */
class InMemoryCbtSessionStoreTest {

    @Test
    void keepsSessionsCreatedAtTheSameInstant() {
        InMemoryCbtSessionStore store = new InMemoryCbtSessionStore();
        Instant same = Instant.parse("2026-11-24T04:00:00.123456Z");

        CbtSession second = new CbtSession("cbt_b", "thread_b", "u-1");
        second.restoreTimes(same, same);
        CbtSession first = new CbtSession("cbt_a", "thread_a", "u-1");
        first.restoreTimes(same, same);
        store.save(second);
        store.save(first);

        List<CbtSession> page1 = store.findVisiblePage("u-1", null, null, null, 1);
        assertEquals(1, page1.size());
        assertEquals("cbt_b", page1.get(0).id(), "同一时刻按 id 倒序，先给大的");

        List<CbtSession> page2 = store.findVisiblePage("u-1", null, same, "cbt_b", 1);
        assertEquals(1, page2.size(), "同一时刻的另一条不能被跳过");
        assertEquals("cbt_a", page2.get(0).id());

        assertEquals(0, store.findVisiblePage("u-1", null, same, "cbt_a", 1).size());
    }

    @Test
    void releaseAccountKeepsSessionsButDropsTheKey() {
        InMemoryCbtSessionStore store = new InMemoryCbtSessionStore();
        CbtSession session = new CbtSession("cbt_r", "thread_r", "u-2");
        session.setAccountId("acc_1");
        store.save(session);

        assertEquals(1, store.findVisible(null, "acc_1").size());
        assertEquals(1, store.releaseAccount("acc_1"));
        assertEquals(0, store.findVisible(null, "acc_1").size(), "注销后账号不再看得到");
        assertEquals(1, store.findVisible("u-2", null).size(), "设备仍然看得到");
    }
}
