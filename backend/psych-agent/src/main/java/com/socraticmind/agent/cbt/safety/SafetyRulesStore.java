package com.socraticmind.agent.cbt.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.agent.config.ConfigHistory;
import com.socraticmind.agent.config.ConfigVersion;
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
 * 安全规则的存储：一份 JSON，后台改完立即生效。
 *
 * 与输出策略、流程画布同一套约定：文件即真值、按 mtime 热加载、坏文件不生效。
 * 但这里的发布门槛更高——{@link SafetyRulesValidator#validateAll} 既查结构，
 * 也用固定用例回放；不通过就拒绝保存，旧规则继续生效。
 *
 * 每次保存都会在 {@code <history-dir>/safety/v<n>.json} 留一份快照；
 * 回滚是"把旧快照当成一次新的保存"，所以每一次生效都能查到当时是什么规则。
 */
@Component
public class SafetyRulesStore {

    private static final Logger log = LoggerFactory.getLogger(SafetyRulesStore.class);

    private final Path path;
    private final Path historyRoot;
    private final ObjectMapper mapper;
    private volatile SafetyRules current;
    private volatile long seenMtime = -1L;
    private boolean warnedMissing;

    public SafetyRulesStore(@Value("${psych.safety.rules-file:config/safety-rules.json}") String file,
                            @Value("${psych.config.history-dir:config/history}") String historyDir,
                            ObjectMapper mapper) {
        this.path = Path.of(file).toAbsolutePath().normalize();
        this.historyRoot = Path.of(historyDir).toAbsolutePath().normalize();
        this.mapper = mapper;
        this.current = SafetyRuleDefaults.rules();
        reloadIfChanged();
        ensureBaseline();
    }

    /** 当前生效的版本如果没有快照（例如配置文件是随包分发的），补一份基线，回滚才有起点。 */
    private void ensureBaseline() {
        if (!Files.exists(path)) return;   // 纯代码默认值不写历史，避免给测试和临时目录制造噪音
        try {
            SafetyRules v = current;
            if (!history().contains(v.version())) {
                ConfigHistory.snapshot(historyRoot, "safety", v.version(), v, mapper);
            }
        } catch (RuntimeException e) {
            log.warn("写安全规则基线快照失败（不影响运行）：{}", e.getMessage());
        }
    }

    public SafetyRules get() {
        reloadIfChanged();
        return current;
    }

    public Path path() {
        return path;
    }

    public List<Integer> history() {
        return ConfigHistory.versions(historyRoot, "safety");
    }

    /** 带时间与操作者的历史列表；只取最近 50 条，避免页面被几百个版本拖死。 */
    public List<ConfigVersion> historyEntries() {
        List<ConfigVersion> out = new ArrayList<>();
        for (Integer v : history()) {
            if (out.size() >= 50) break;
            SafetyRules s = snapshot(v);
            if (s != null) out.add(new ConfigVersion(v, s.updatedAt(), s.updatedBy()));
        }
        return out;
    }

    public SafetyRules snapshot(int version) {
        return ConfigHistory.read(historyRoot, "safety", version, SafetyRules.class, mapper);
    }

    public synchronized SafetyRules save(SafetyRules incoming, String actor) {
        SafetyRules normalized = normalize(incoming);
        List<String> errors = SafetyRulesValidator.validateAll(normalized);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(summarize(errors));
        }
        SafetyRules next = new SafetyRules(
                current.version() + 1,
                Instant.now().toString(),
                actor == null || actor.isBlank() ? "ops" : actor.trim(),
                normalized.rules(),
                normalized.cases());
        try {
            write(next);
        } catch (IOException e) {
            throw new IllegalStateException("安全规则写入失败：" + e.getMessage(), e);
        }
        ConfigHistory.snapshot(historyRoot, "safety", next.version(), next, mapper);
        current = next;
        seenMtime = mtime();
        log.info("安全规则已保存：v{}，{} 条，写入 {}", next.version(), next.rules().size(), path);
        return next;
    }

    /** 回滚 = 把历史快照当成一次新的保存，历史只追加、不改写。 */
    public synchronized SafetyRules rollback(int version, String actor) {
        SafetyRules snapshot = snapshot(version);
        if (snapshot == null) {
            throw new IllegalArgumentException("没有这个历史版本：v" + version);
        }
        String who = actor == null || actor.isBlank() ? "ops" : actor.trim();
        return save(snapshot, who + " · rollback v" + version);
    }

    private void write(SafetyRules next) throws IOException {
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
                log.warn("安全规则文件不存在（{}），使用内置默认规则；在后台保存会创建该文件", path);
                warnedMissing = true;
            }
            return;
        }
        try {
            long mtime = mtime();
            if (mtime == seenMtime) return;
            SafetyRules loaded = mapper.readValue(path.toFile(), SafetyRules.class);
            List<String> errors = SafetyRulesValidator.validateAll(loaded);
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException(String.join("；", errors));
            }
            current = normalize(loaded);
            seenMtime = mtime;
            log.info("安全规则已热加载：v{}，{} 条", current.version(), current.rules().size());
        } catch (Exception e) {
            // 坏文件不生效：继续用上一版规则，并留下可查的日志，避免"静默变成无规则"。
            seenMtime = mtime();
            log.error("安全规则文件不可用，继续使用 v{}：{}", current.version(), e.getMessage());
        }
    }

    private long mtime() {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }

    private static SafetyRules normalize(SafetyRules def) {
        if (def == null) throw new IllegalArgumentException("规则集不能为空");
        List<SafetyRule> rules = new ArrayList<>();
        if (def.rules() != null) {
            for (SafetyRule r : def.rules()) {
                if (r == null) continue;
                rules.add(new SafetyRule(
                        trim(r.id()), trim(r.label()), trim(r.level()), trim(r.pattern()),
                        r.enabled(), trim(r.note())));
            }
        }
        // 旧快照没有 cases 字段（null）：补出厂用例；明确删空（空列表）则原样保留，交给校验拒绝。
        List<SafetyCase> cases = new ArrayList<>();
        if (def.cases() == null) {
            cases.addAll(SafetyRuleDefaults.defaultCases());
        } else {
            for (SafetyCase c : def.cases()) {
                if (c == null) continue;
                cases.add(new SafetyCase(trim(c.text()), trim(c.expect()), trim(c.why())));
            }
        }
        return new SafetyRules(def.version(), def.updatedAt(), def.updatedBy(), rules, cases);
    }

    private static String trim(String v) {
        return v == null ? null : v.trim();
    }

    /** 保存失败时只回前三条：十一条用例的完整清单留给"校验草稿"接口，别塞进一个错误弹窗。 */
    private static String summarize(List<String> errors) {
        if (errors.size() <= 3) return String.join("；", errors);
        return String.join("；", errors.subList(0, 3)) + "；等共 " + errors.size() + " 条";
    }
}
