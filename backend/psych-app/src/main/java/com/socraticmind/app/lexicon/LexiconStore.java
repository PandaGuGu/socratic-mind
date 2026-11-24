package com.socraticmind.app.lexicon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.agent.config.ConfigHistory;
import com.socraticmind.agent.config.ConfigVersion;
import com.socraticmind.common.guard.SensitiveLexicon;
import com.socraticmind.common.guard.SensitiveLexicon.Action;
import com.socraticmind.common.guard.SensitiveLexicon.Entry;
import com.socraticmind.common.guard.SensitiveLexicon.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感词库的存储：一份文本文件，后台改完保存即生效。
 *
 * 与安全规则、输出策略、流程画布同一套约定——文件即真值、坏文件不生效、每次保存留快照。
 * 但它比另外几份更需要门槛：词库有两类错误是"看起来改对了、实际更糟"：
 * <ol>
 *   <li>把 BLOCK 类删光：页面还能打开，输入侧却完全不拦了；</li>
 *   <li>把范围写成全部 OUT：用户输入侧空转，等于关掉过滤。</li>
 * </ol>
 * 所以校验里硬性要求"至少一条 BLOCK"与"至少一条覆盖输入侧"，不靠人记得。
 *
 * 文件格式刻意选纯文本而不是 JSON：词库是运维每天要看、要 diff 的东西，
 * 一行一个词比缩进层级更好 review。版本与操作者写在注释头里，解析时跳过。
 */
@Component
public class LexiconStore {

    private static final Logger log = LoggerFactory.getLogger(LexiconStore.class);
    private static final int MIN_ENTRIES = 10;
    private static final int MAX_ENTRIES = 2000;
    private static final int MAX_WORD = 32;
    private static final int MAX_CATEGORY = 24;
    private static final Pattern HEADER = Pattern.compile("^#\\s*(version|updated|by)\\s*[:：]\\s*(.*)$");

    /** 页面与接口共用的一条词条；normalized 只是回显，保存时不会被信任。 */
    public record Row(String scope, String category, String action, String word) {

        static Row of(Entry e) {
            return new Row(e.scope().name(), e.category(), e.action().name(), e.word());
        }
    }

    /** 历史快照。字段与文件同构，回滚时可以直接当一次新的保存。 */
    public record Snapshot(int version, String updatedAt, String updatedBy, List<Row> entries) {
    }

    public record View(int version, String updatedAt, String updatedBy, String path, boolean persisted,
                       int count, List<Row> entries, List<ConfigVersion> history) {
    }

    private final Path path;
    private final Path historyRoot;
    private final ObjectMapper mapper;

    private volatile int version;
    private volatile String updatedAt;
    private volatile String updatedBy;
    private volatile boolean persisted;

    public LexiconStore(@Value("${psych.lexicon.file:config/lexicon.txt}") String file,
                        @Value("${psych.config.history-dir:config/history}") String historyDir,
                        ObjectMapper mapper) {
        this.path = Path.of(file).toAbsolutePath().normalize();
        this.historyRoot = Path.of(historyDir).toAbsolutePath().normalize();
        this.mapper = mapper;
        this.version = 0;
        this.updatedAt = null;
        this.updatedBy = "builtin";
        this.persisted = false;
        loadFile();
    }

    public View view() {
        List<Entry> entries = SensitiveLexicon.get().entries();
        List<Row> rows = entries.stream().map(Row::of).toList();
        return new View(version, updatedAt, updatedBy, path.toString(), persisted,
                rows.size(), rows, history());
    }

    public Path path() {
        return path;
    }

    /**
     * 保存一份完整词库（不是增量）。失败时不改变任何状态——旧词库继续生效，
     * 因为"改错一半"比"没改成"危险得多。
     */
    public synchronized View save(List<Row> incoming, String actor) {
        List<Entry> entries = normalize(incoming);
        List<String> errors = check(entries);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("；", errors));
        }
        int nextVersion = version + 1;
        String now = Instant.now().toString();
        String who = actor == null || actor.isBlank() ? "ops" : actor.strip();
        try {
            write(nextVersion, now, who, entries);
        } catch (IOException e) {
            throw new IllegalStateException("词库写入失败：" + e.getMessage(), e);
        }
        ConfigHistory.snapshot(historyRoot, "lexicon",
                nextVersion, new Snapshot(nextVersion, now, who, entries.stream().map(Row::of).toList()), mapper);
        SensitiveLexicon.install(entries);
        applyMeta(nextVersion, now, who, true);
        log.info("敏感词库已保存：v{}，{} 条，写入 {}", nextVersion, entries.size(), path);
        return view();
    }

    /** 恢复随包基线：不是"删文件"，而是一次新的保存，历史仍可追溯。 */
    public synchronized View reset(String actor) {
        String who = actor == null || actor.isBlank() ? "ops" : actor.strip();
        return save(SensitiveLexicon.builtIn().stream().map(Row::of).toList(), who + " · reset builtin");
    }

    /**
     * 回滚到某一版：不是"恢复旧文件"，而是把旧快照当成一次新的保存。
     * 于是版本号继续往前走，每一次生效都有快照可查，不会出现"回滚后历史与当前不一致"。
     */
    public synchronized View rollback(int target, String actor) {
        Snapshot snapshot = snapshot(target);
        if (snapshot == null) {
            throw new IllegalArgumentException("没有这个历史版本：v" + target);
        }
        String who = actor == null || actor.isBlank() ? "ops" : actor.strip();
        return save(snapshot.entries(), who + " · rollback v" + target);
    }

    public Snapshot snapshot(int target) {
        return ConfigHistory.read(historyRoot, "lexicon", target, Snapshot.class, mapper);
    }

    public List<ConfigVersion> history() {
        List<ConfigVersion> out = new ArrayList<>();
        for (Integer v : ConfigHistory.versions(historyRoot, "lexicon")) {
            if (out.size() >= 20) break;
            Snapshot s = snapshot(v);
            if (s != null) out.add(new ConfigVersion(v, s.updatedAt(), s.updatedBy()));
        }
        return out;
    }

    /** 只读校验：页面点"保存"之前可以先看会失败在哪一条，不落盘。 */
    public List<String> validate(List<Row> rows) {
        return check(normalize(rows));
    }

    private void loadFile() {
        if (!Files.exists(path)) {
            // 文件被删但历史还在时，版本号不能退回 0：否则下一次保存会覆盖旧的 v1 快照，
            // 与"历史只追加"直接冲突。
            int fromHistory = ConfigHistory.versions(historyRoot, "lexicon").stream()
                    .findFirst().orElse(0);
            applyMeta(fromHistory, null, "builtin", false);
            log.info("词库文件不存在（{}），使用随包基线：{} 条；历史最高 v{}",
                    path, SensitiveLexicon.builtIn().size(), fromHistory);
            return;
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            List<Entry> entries = new ArrayList<>();
            int fileVersion = 0;
            String at = null;
            String by = null;
            for (String raw : lines) {
                String line = raw.strip();
                if (line.isEmpty()) continue;
                if (line.startsWith("#")) {
                    Matcher m = HEADER.matcher(line);
                    if (!m.matches()) continue;
                    String key = m.group(1);
                    String value = m.group(2).strip();
                    if ("version".equals(key)) {
                        try {
                            fileVersion = Integer.parseInt(value);
                        } catch (NumberFormatException ignored) {
                            // 版本头坏了不影响词条；当作 0，保存时自然往前推一版。
                        }
                    } else if ("updated".equals(key)) {
                        at = value;
                    } else {
                        by = value;
                    }
                    continue;
                }
                Entry entry = parseLine(line);
                if (entry == null) {
                    throw new IllegalArgumentException("有一行不是 scope|category|action|word：" + line);
                }
                entries.add(entry);
            }
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("文件里没有可用词条");
            }
            List<String> errors = check(entries);
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException(String.join("；", errors));
            }
            SensitiveLexicon.install(entries);
            int fromHistory = ConfigHistory.versions(historyRoot, "lexicon").stream()
                    .findFirst().orElse(0);
            applyMeta(Math.max(fileVersion, fromHistory), at, by == null ? "file" : by, true);
            log.info("敏感词库已加载：v{}，{} 条，来自 {}", version, entries.size(), path);
        } catch (Exception e) {
            // 坏文件不生效：宁可继续用基线，也不要因为一次手滑让输入侧变成不设防。
            log.error("词库文件不可用，继续使用随包基线：{}", e.getMessage());
        }
    }

    private void applyMeta(int v, String at, String by, boolean onDisk) {
        this.version = v;
        this.updatedAt = at;
        this.updatedBy = by;
        this.persisted = onDisk;
    }

    private void write(int v, String at, String by, List<Entry> entries) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        StringBuilder sb = new StringBuilder();
        sb.append("# Socratic Mind 敏感词库\n");
        sb.append("# 一行一条：scope|category|action|word\n");
        sb.append("# scope = in / out / both；action = block / mask / review\n");
        sb.append("# version: ").append(v).append('\n');
        sb.append("# updated: ").append(at).append('\n');
        sb.append("# by: ").append(by).append('\n');
        for (Entry e : entries) {
            sb.append(e.scope().name().toLowerCase(Locale.ROOT)).append('|')
                    .append(e.category()).append('|')
                    .append(e.action().name().toLowerCase(Locale.ROOT)).append('|')
                    .append(e.word()).append('\n');
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Entry parseLine(String line) {
        String[] parts = line.split("\\|", 4);
        if (parts.length < 4) return null;
        try {
            return Entry.of(Scope.valueOf(parts[0].strip().toUpperCase(Locale.ROOT)),
                    parts[1], Action.valueOf(parts[2].strip().toUpperCase(Locale.ROOT)), parts[3]);
        } catch (IllegalArgumentException e) {
            // 单行坏了：跳过它，并在校验阶段以外的地方不制造启动失败。
            return null;
        }
    }

    private static List<Entry> normalize(List<Row> rows) {
        if (rows == null) throw new IllegalArgumentException("词库不能为空");
        List<Entry> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            if (r == null) continue;
            Scope scope = parseScope(r.scope());
            Action action = parseAction(r.action());
            out.add(Entry.of(scope, r.category(), action, r.word()));
        }
        return out;
    }

    private static Scope parseScope(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("范围不能为空");
        try {
            return Scope.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("范围只能是 in / out / both：" + value);
        }
    }

    private static Action parseAction(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("动作不能为空");
        try {
            return Action.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("动作只能是 block / mask / review：" + value);
        }
    }

    private static List<String> check(List<Entry> entries) {
        List<String> errors = new ArrayList<>();
        if (entries.size() < MIN_ENTRIES) {
            errors.add("词库至少保留 " + MIN_ENTRIES + " 条，当前 " + entries.size() + " 条");
        }
        if (entries.size() > MAX_ENTRIES) {
            errors.add("词库最多 " + MAX_ENTRIES + " 条，当前 " + entries.size() + " 条");
        }
        Set<String> seen = new LinkedHashSet<>();
        int block = 0;
        int input = 0;
        for (Entry e : entries) {
            String word = e.word();
            if (word == null || word.isBlank()) {
                errors.add("有词条没填词");
                continue;
            }
            if (word.length() > MAX_WORD) {
                errors.add("词超过 " + MAX_WORD + " 字：" + word);
            }
            if (word.contains("|")) {
                errors.add("词里不能有竖线：" + word);
            }
            String category = e.category();
            if (category == null || category.isBlank()) {
                errors.add("词条缺少分类：" + word);
            } else if (category.length() > MAX_CATEGORY) {
                errors.add("分类名超过 " + MAX_CATEGORY + " 字：" + category);
            }
            if (e.action() == Action.BLOCK) block++;
            if (e.scope() == Scope.IN || e.scope() == Scope.BOTH) input++;
            if (!seen.add(e.scope() + "|" + word)) {
                errors.add("重复词条：" + e.scope().name().toLowerCase(Locale.ROOT) + " / " + word);
            }
        }
        // 两条底线：还能拦住东西、输入侧仍然有词。删空之后"看起来保存成功"是最坏的结果。
        if (block == 0) errors.add("至少要留一条 block 词条，否则输入侧不再拦截");
        if (input == 0) errors.add("至少要留一条 in / both 词条，否则用户输入侧空转");
        return errors.size() > 8 ? errors.subList(0, 8) : errors;
    }
}
