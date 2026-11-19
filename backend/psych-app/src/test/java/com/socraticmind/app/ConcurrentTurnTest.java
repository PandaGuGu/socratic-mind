package com.socraticmind.app;

import com.socraticmind.agent.cbt.CbtFlow;
import com.socraticmind.session.CbtSession;
import com.socraticmind.session.CbtSessionStore;
import com.socraticmind.session.CbtStage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 同一会话的并发消息必须串行，否则"读阶段 → 改阶段 → 整轮落库"会互相覆盖。
 *
 * 两个请求同时打到同一条会话时，正确处理是：第一条推进到「想法」，
 * 第二条在「想法」的基础上继续推进到「检验」——两条话都留下来。
 * 若没有串行保护，两条都会从「情境」出发，只推进一步，另一条被覆盖。
 */
@SpringBootTest
class ConcurrentTurnTest {

    @Autowired
    CbtFlow flow;

    @Autowired
    CbtSessionStore store;

    @Test
    void parallelMessagesAdvanceOnceEachAndKeepBoth() throws Exception {
        String id = flow.start("u-concurrent").sessionId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> {
                gate.await();
                return flow.handle(id, "下周组会汇报，一想到就手心出汗");
            });
            Future<?> second = pool.submit(() -> {
                gate.await();
                return flow.handle(id, "我肯定会搞砸");
            });
            gate.countDown();

            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        CbtSession session = store.find(id).orElseThrow();
        assertEquals(CbtStage.COGNITIVE_REVIEW, session.stage(),
                "两次推进必须都生效，而不是覆盖成同一步");

        long userMessages = session.history().stream().filter(m -> "user".equals(m.role())).count();
        assertEquals(2, userMessages, "两条用户消息都不能丢");
        assertFalse(session.history().isEmpty());
    }
}
