package com.socraticmind.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行参数：改得动、改不坏、退得回。
 *
 * 这里的"改不坏"是范围校验——数值没有语义对错，但 0 条/分钟和 10 万字上限都是事故。
 */
class RuntimeLimitsStoreTest {

    @TempDir
    Path dir;

    private RuntimeLimitsStore store() {
        return new RuntimeLimitsStore(
                dir.resolve("limits.json").toString(),
                dir.resolve("history").toString(),
                new ObjectMapper());
    }

    @Test
    void savesValidValuesAndRejectsAbsurdOnes() {
        RuntimeLimitsStore store = store();
        assertEquals(2000, store.get().maxMessageChars());

        RuntimeLimits saved = store.save(new RuntimeLimits(0, null, "test", 800, 30, 10), "ops");
        assertEquals(2, saved.version());
        assertEquals(800, store.get().maxMessageChars());

        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> store.save(new RuntimeLimits(0, null, "test", 800, 0, 10), "ops"));
        assertTrue(zero.getMessage().contains("每分钟"), zero.getMessage());

        IllegalArgumentException huge = assertThrows(IllegalArgumentException.class,
                () -> store.save(new RuntimeLimits(0, null, "test", 999999, 30, 10), "ops"));
        assertTrue(huge.getMessage().contains("上限"), huge.getMessage());

        assertEquals(2, store.get().version(), "被拒的保存不能推进版本");
    }

    @Test
    void rollbackRestoresPreviousNumbers() {
        RuntimeLimitsStore store = store();
        store.save(new RuntimeLimits(0, null, "a", 800, 30, 10), "a");
        store.save(new RuntimeLimits(0, null, "b", 1200, 20, 5), "b");

        assertEquals(List.of(3, 2), store.history());
        RuntimeLimits rolled = store.rollback(2, "c");
        assertEquals(800, rolled.maxMessageChars());
        assertEquals(4, rolled.version());
    }
}
