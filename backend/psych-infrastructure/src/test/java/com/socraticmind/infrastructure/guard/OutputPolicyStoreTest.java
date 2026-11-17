package com.socraticmind.infrastructure.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.common.guard.OutputPolicy;
import com.socraticmind.common.guard.PolicyProfiles;
import com.socraticmind.common.guard.ProfileRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 策略存储：默认值、保存、校验、外部编辑后的热加载。 */
class OutputPolicyStoreTest {

    @TempDir
    Path dir;

    private OutputPolicyStore store() {
        return new OutputPolicyStore(dir.resolve("policy.json").toString(), new ObjectMapper());
    }

    @Test
    void startsFromBuiltInDefaultsWhenFileIsMissing() {
        OutputPolicyStore store = store();
        assertFalse(store.exists());
        assertEquals(1, store.get().version());
        assertTrue(store.get().ruleOf(PolicyProfiles.CBT_BALANCE).enabled(),
                "默认档位应当是启用状态，否则守卫形同虚设");
    }

    @Test
    void saveWritesFileAndBumpsVersion() {
        OutputPolicyStore store = store();
        OutputPolicy saved = store.save(OutputPolicy.defaults(), "ops");

        assertEquals(2, saved.version());
        assertEquals("ops", saved.updatedBy());
        assertTrue(store.exists());
        assertEquals(2, store.get().version());
        assertTrue(Files.exists(store.path()));
    }

    @Test
    void invalidThresholdIsRejectedBeforeWriting() {
        OutputPolicyStore store = store();
        OutputPolicy broken = new OutputPolicy(1, null, "ops", Map.of(
                PolicyProfiles.CBT_SUMMARY,
                new ProfileRule(true, 90, 20, List.of(), 0.2, 1, "最短大于最长")));

        assertThrows(IllegalArgumentException.class, () -> store.save(broken, "ops"));
        assertFalse(store.exists(), "校验失败不该落盘");
    }

    @Test
    void externalEditIsPickedUpWithoutRestart() throws Exception {
        Path file = dir.resolve("policy.json");
        OutputPolicyStore store = new OutputPolicyStore(file.toString(), new ObjectMapper());
        assertEquals(1, store.get().version());

        OutputPolicy edited = new OutputPolicy(7, null, "ops", Map.of(
                PolicyProfiles.CBT_BALANCE,
                new ProfileRule(true, 8, 50, List.of("你应该"), 0.2, 2, "手工改的")));
        new ObjectMapper().writeValue(file.toFile(), edited);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 2_000));

        assertEquals(7, store.get().version(), "文件变了就必须重读，不需要重启");
        assertEquals(50, store.get().ruleOf(PolicyProfiles.CBT_BALANCE).maxChars());
    }

    @Test
    void brokenFileKeepsLastGoodPolicy() throws Exception {
        Path file = dir.resolve("policy.json");
        OutputPolicyStore store = new OutputPolicyStore(file.toString(), new ObjectMapper());
        int versionBefore = store.get().version();

        Files.writeString(file, "{ not json");
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 2_000));

        assertEquals(versionBefore, store.get().version(), "坏文件不能让策略失效");
    }
}
