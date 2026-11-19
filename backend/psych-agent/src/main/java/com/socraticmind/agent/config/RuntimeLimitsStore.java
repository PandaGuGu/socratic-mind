package com.socraticmind.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 运行参数的存储：一份 JSON，后台改完立即生效。
 *
 * 与安全规则、流程画布同一套约定（文件即真值、mtime 热加载、历史快照、回滚即新保存）。
 * 校验是"范围校验"而不是"回放"：这些数字没有语义对错，只有合理区间。
 */
@Component
public class RuntimeLimitsStore {

    private static final Logger log = LoggerFactory.getLogger(RuntimeLimitsStore.class);

    private final Path path;
    private final Path historyRoot;
    private final ObjectMapper mapper;
    private volatile RuntimeLimits current;
    private volatile long seenMtime = -1L;
    private boolean warnedMissing;

    public RuntimeLimitsStore(@Value("${psych.limits.file:config/limits.json}") String file,
                              @Value("${psych.config.history-dir:config/history}") String historyDir,
                              ObjectMapper mapper) {
        this.path = Path.of(file).toAbsolutePath().normalize();
        this.historyRoot = Path.of(historyDir).toAbsolutePath().normalize();
        this.mapper = mapper;
        this.current = RuntimeLimits.defaults();
        reloadIfChanged();
        ensureBaseline();
    }

    /** 当前生效的版本如果没有快照（例如配置文件是随包分发的），补一份基线，回滚才有起点。 */
    private void ensureBaseline() {
        if (!Files.exists(path)) return;
        try {
            RuntimeLimits v = current;
            if (!history().contains(v.version())) {
                ConfigHistory.snapshot(historyRoot, "limits", v.version(), v, mapper);
            }
        } catch (RuntimeException e) {
            log.warn("写运行参数基线快照失败（不影响运行）：{}", e.getMessage());
        }
    }

    public RuntimeLimits get() {
        reloadIfChanged();
        return current;
    }

    public Path path() {
        return path;
    }

    public List<Integer> history() {
        return ConfigHistory.versions(historyRoot, "limits");
    }

    /** 带时间与操作者的历史列表；只取最近 50 条。 */
    public List<ConfigVersion> historyEntries() {
        List<ConfigVersion> out = new ArrayList<>();
        for (Integer v : history()) {
            if (out.size() >= 50) break;
            RuntimeLimits s = snapshot(v);
            if (s != null) out.add(new ConfigVersion(v, s.updatedAt(), s.updatedBy()));
        }
        return out;
    }

    public RuntimeLimits snapshot(int version) {
        return ConfigHistory.read(historyRoot, "limits", version, RuntimeLimits.class, mapper);
    }

    public synchronized RuntimeLimits save(RuntimeLimits incoming, String actor) {
        if (incoming == null) throw new IllegalArgumentException("参数不能为空");
        List<String> errors = validate(incoming);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));

        RuntimeLimits next = new RuntimeLimits(
                current.version() + 1,
                Instant.now().toString(),
                actor == null || actor.isBlank() ? "ops" : actor.trim(),
                incoming.maxMessageChars(),
                incoming.messagesPerMinute(),
                incoming.newSessionsPerHour());
        try {
            write(next);
        } catch (IOException e) {
            throw new IllegalStateException("运行参数写入失败：" + e.getMessage(), e);
        }
        ConfigHistory.snapshot(historyRoot, "limits", next.version(), next, mapper);
        current = next;
        seenMtime = mtime();
        log.info("运行参数已保存：v{}，消息上限 {} 字，{} 条/分钟，{} 次开新会话/小时",
                next.version(), next.maxMessageChars(), next.messagesPerMinute(), next.newSessionsPerHour());
        return next;
    }

    public synchronized RuntimeLimits rollback(int version, String actor) {
        RuntimeLimits snapshot = snapshot(version);
        if (snapshot == null) throw new IllegalArgumentException("没有这个历史版本：v" + version);
        String who = actor == null || actor.isBlank() ? "ops" : actor.trim();
        return save(snapshot, who + " · rollback v" + version);
    }

    public static List<String> validate(RuntimeLimits v) {
        List<String> errors = new ArrayList<>();
        if (v.maxMessageChars() < 100 || v.maxMessageChars() > 20000) {
            errors.add("单条消息上限必须在 100–20000 字之间（当前 " + v.maxMessageChars() + "）");
        }
        if (v.messagesPerMinute() < 1 || v.messagesPerMinute() > 1000) {
            errors.add("每分钟消息数必须在 1–1000 之间（当前 " + v.messagesPerMinute() + "）");
        }
        if (v.newSessionsPerHour() < 1 || v.newSessionsPerHour() > 10000) {
            errors.add("每小时开新会话上限必须在 1–10000 之间（当前 " + v.newSessionsPerHour() + "）");
        }
        return errors;
    }

    private void write(RuntimeLimits next) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(next);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void reloadIfChanged() {
        if (!Files.exists(path)) {
            if (!warnedMissing) {
                log.warn("运行参数文件不存在（{}），使用内置默认值；在后台保存会创建该文件", path);
                warnedMissing = true;
            }
            return;
        }
        try {
            long mtime = mtime();
            if (mtime == seenMtime) return;
            RuntimeLimits loaded = mapper.readValue(path.toFile(), RuntimeLimits.class);
            List<String> errors = validate(loaded);
            if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
            current = loaded;
            seenMtime = mtime;
            log.info("运行参数已热加载：v{}", loaded.version());
        } catch (Exception e) {
            seenMtime = mtime();
            log.error("运行参数文件不可用，继续使用 v{}：{}", current.version(), e.getMessage());
        }
    }

    private long mtime() {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }
}
