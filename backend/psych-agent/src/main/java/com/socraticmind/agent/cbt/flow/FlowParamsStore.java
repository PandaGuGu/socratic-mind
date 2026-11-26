package com.socraticmind.agent.cbt.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.agent.cbt.CbtScript;
import com.socraticmind.agent.config.ConfigHistory;
import com.socraticmind.agent.config.ConfigVersion;
import com.socraticmind.session.CbtStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 流程画布库：多张画布、每张画布独立的草稿与已启用版本。
 *
 * 为什么不是一份 JSON：运营需要并存"默认流程""儿童版""危机版"，并在启用前先存草稿。
 * 规则仍然是"文件即真值"：
 * <pre>
 *   config/flows/index.json                 画布清单与当前启用的一张
 *   config/flows/&lt;id&gt;/draft.json            正在编辑的草稿
 *   config/flows/&lt;id&gt;/published.json        线上生效的版本
 *   config/history/flow/v&lt;n&gt;.json           默认画布的发布快照（兼容旧历史）
 *   config/history/flow-&lt;id&gt;/v&lt;n&gt;.json      其他画布的发布快照
 * </pre>
 * 运行时只读"当前启用画布的 published"；草稿改坏了不会影响线上。
 */
@Component
public class FlowParamsStore {

    private static final Logger log = LoggerFactory.getLogger(FlowParamsStore.class);

    public static final String DEFAULT_CANVAS_ID = "default";
    private static final String DEFAULT_CANVAS_NAME = "默认流程";

    private final Path legacyPath;
    private final Path flowsRoot;
    private final Path indexPath;
    private final Path historyRoot;
    private final ObjectMapper mapper;
    private final CbtScript script = new CbtScript();

    private volatile String activeId = DEFAULT_CANVAS_ID;
    private volatile List<CanvasMeta> metas = List.of();
    private volatile FlowDefinition published;
    private volatile long indexMtime = -1L;
    private volatile long publishedMtime = -1L;

    @org.springframework.beans.factory.annotation.Autowired
    public FlowParamsStore(@Value("${psych.flow.params-file:config/flow-params.json}") String file,
                           @Value("${psych.config.history-dir:config/history}") String historyDir,
                           ObjectMapper mapper) {
        this(file, historyDir, mapper, standardFlowsRoot(file));
    }

    /** 无容器环境（测试、脚本）用：历史目录取配置文件同级，画布目录按文件名隔离。 */
    public FlowParamsStore(String file, ObjectMapper mapper) {
        this(file, siblingHistory(file), mapper, isolatedFlowsRoot(file));
    }

    private FlowParamsStore(String file, String historyDir, ObjectMapper mapper, Path flowsRoot) {
        this.legacyPath = Path.of(file).toAbsolutePath().normalize();
        this.flowsRoot = flowsRoot;
        this.indexPath = flowsRoot.resolve("index.json");
        this.historyRoot = Path.of(historyDir).toAbsolutePath().normalize();
        this.mapper = mapper;
        ensureLibrary();
        reloadIfChanged();
    }

    private static Path standardFlowsRoot(String file) {
        Path parent = Path.of(file).toAbsolutePath().normalize().getParent();
        return (parent == null ? Path.of("config").toAbsolutePath() : parent).resolve("flows");
    }

    private static Path isolatedFlowsRoot(String file) {
        Path normalized = Path.of(file).toAbsolutePath().normalize();
        String name = normalized.getFileName().toString().replaceAll("[^a-zA-Z0-9_-]", "");
        Path parent = normalized.getParent() == null ? Path.of("config").toAbsolutePath() : normalized.getParent();
        return parent.resolve("flows-" + name);
    }

    private static String siblingHistory(String file) {
        Path parent = Path.of(file).toAbsolutePath().normalize().getParent();
        return (parent == null ? Path.of("config") : parent).resolve("history").toString();
    }

    /* ---------------- 清单与元数据 ---------------- */

    public record CanvasMeta(String id, String name, String description,
                             String createdAt, String updatedAt,
                             int draftVersion, int publishedVersion, boolean active) {
    }

    private record CanvasFile(String id, String name, String description, String createdAt, String updatedAt) {
    }

    private record Library(String activeId, List<CanvasFile> canvases) {
    }

    public List<CanvasMeta> canvases() {
        reloadIfChanged();
        return metas;
    }

    public String activeId() {
        reloadIfChanged();
        return activeId;
    }

    public CanvasMeta activeCanvas() {
        reloadIfChanged();
        return metas.stream().filter(CanvasMeta::active).findFirst().orElse(metaOrNull(activeId));
    }

    public CanvasMeta canvas(String id) {
        reloadIfChanged();
        return metaOrNull(id);
    }

    private CanvasMeta metaOrNull(String id) {
        return metas.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
    }

    /** 当前启用画布的线上定义。执行器只读这个。 */
    public FlowDefinition get() {
        reloadIfChanged();
        return published == null ? defaults() : published;
    }

    public FlowDefinition draft(String id) {
        FlowDefinition draft = readDefinition(draftPath(id));
        if (draft != null) return draft;
        FlowDefinition pub = readDefinition(publishedPath(id));
        if (pub != null) return pub;
        return defaults();
    }

    public FlowDefinition published(String id) {
        FlowDefinition pub = readDefinition(publishedPath(id));
        return pub == null ? defaults() : pub;
    }

    public Path path() {
        reloadIfChanged();
        return draftPath(activeId);
    }

    public Path publishedPath() {
        reloadIfChanged();
        return publishedPath(activeId);
    }

    public boolean exists() {
        reloadIfChanged();
        return Files.exists(publishedPath(activeId));
    }

    /* ---------------- 保存 / 发布 ---------------- */

    /** 兼容旧调用：保存并立即发布当前画布。测试与"保存并发布"类页面用它。 */
    public synchronized FlowDefinition save(FlowDefinition incoming, String updatedBy) {
        String id = activeId;
        saveDraft(id, incoming, updatedBy);
        return publish(id, updatedBy);
    }

    /** 只保存草稿，不改变线上流程。 */
    public synchronized FlowDefinition saveDraft(String id, FlowDefinition incoming, String updatedBy) {
        ensureCanvas(id);
        FlowDefinition normalized = normalize(incoming);
        FlowDefinition draft = readDefinition(draftPath(id));
        FlowDefinition pub = readDefinition(publishedPath(id));
        int base = Math.max(draft == null ? 0 : draft.version(), pub == null ? 0 : pub.version());
        FlowDefinition next = new FlowDefinition(base + 1, Instant.now().toString(), who(updatedBy),
                normalized.stages(), normalized.intervention(), normalized.messages(), normalized.start());
        writeDefinition(draftPath(id), next);
        touchMeta(id);
        log.info("流程草稿已保存：画布 {}，版本 {}", id, next.version());
        return next;
    }

    /**
     * 发布草稿并把它设为当前启用画布。
     *
     * 发布前跑完整校验；草稿可以是半成品，但线上永远是一份通过结构检查的流程。
     */
    public synchronized FlowDefinition publish(String id, String updatedBy) {
        ensureCanvas(id);
        FlowDefinition draft = readDefinition(draftPath(id));
        if (draft == null) throw new IllegalArgumentException("画布还没有草稿：" + id);
        List<String> errors = FlowValidator.validate(draft);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));

        FlowDefinition pub = readDefinition(publishedPath(id));
        int version = draft.version();
        if (pub != null) {
            if (sameContent(pub, draft)) {
                version = pub.version();
            } else if (version <= pub.version()) {
                version = pub.version() + 1;
            }
        }
        FlowDefinition next = new FlowDefinition(version, Instant.now().toString(), who(updatedBy),
                draft.stages(), draft.intervention(), draft.messages(), draft.start());
        writeDefinition(publishedPath(id), next);
        ConfigHistory.snapshot(historyRoot, historyKind(id), next.version(), next, mapper);

        // 先更新元数据，再切 activeId：touchMeta 会按旧清单重写 index，
        // 如果顺序反过来，刚设置的启用画布会被旧 activeId 覆盖掉。
        touchMeta(id);
        activeId = id;
        published = next;
        writeIndex();
        reloadIfChanged(true);
        log.info("流程画布已启用：{} v{}，写入 {}", id, next.version(), publishedPath(id));
        return next;
    }

    /** 系统话术是流程的一部分：保存后立即发布，不经过画布草稿，保持原有"保存即生效"语义。 */
    public synchronized FlowDefinition saveMessages(Map<String, String> messages, String updatedBy) {
        String id = activeId;
        FlowDefinition draft = readDefinition(draftPath(id));
        if (draft == null) draft = get();
        FlowDefinition pub = readDefinition(publishedPath(id));
        int base = Math.max(draft.version(), pub == null ? 0 : pub.version());
        Map<String, String> merged = new LinkedHashMap<>(draft.messages());
        if (messages != null) {
            for (Map.Entry<String, String> e : messages.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) merged.put(e.getKey(), e.getValue());
            }
        }
        FlowDefinition next = new FlowDefinition(base + 1, Instant.now().toString(), who(updatedBy),
                draft.stages(), draft.intervention(), merged, draft.start());
        List<String> errors = FlowValidator.validate(next);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
        writeDefinition(draftPath(id), next);
        writeDefinition(publishedPath(id), next);
        ConfigHistory.snapshot(historyRoot, historyKind(id), next.version(), next, mapper);
        published = next;
        touchMeta(id);
        log.info("系统话术已发布：画布 {} v{}", id, next.version());
        return next;
    }

    /* ---------------- 画布的增删改选 ---------------- */

    public synchronized CanvasMeta createCanvas(String name, String description,
                                                String copyFrom, String updatedBy) {
        String id = "cv-" + UUID.randomUUID().toString().substring(0, 8);
        FlowDefinition seed = copyFrom == null || copyFrom.isBlank() ? defaults() : draft(copyFrom);
        FlowDefinition next = new FlowDefinition(1, Instant.now().toString(), who(updatedBy),
                seed.stages(), seed.intervention(), seed.messages(), seed.start());
        writeDefinition(draftPath(id), next);
        writeDefinition(publishedPath(id), next);
        ConfigHistory.snapshot(historyRoot, historyKind(id), 1, next, mapper);
        String now = Instant.now().toString();
        Library lib = readLibrary();
        List<CanvasFile> files = new ArrayList<>(lib.canvases());
        files.add(new CanvasFile(id, safeName(name, "新画布"), description == null ? "" : description.trim(), now, now));
        writeLibrary(new Library(lib.activeId(), files));
        reloadIfChanged(true);
        log.info("画布已创建：{}（复制自 {}）", id, copyFrom);
        return metaOrNull(id);
    }

    public synchronized CanvasMeta updateCanvas(String id, String name, String description) {
        ensureCanvas(id);
        Library lib = readLibrary();
        List<CanvasFile> files = lib.canvases().stream().map(f -> f.id().equals(id)
                ? new CanvasFile(f.id(), safeName(name, f.name()),
                description == null ? f.description() : description.trim(), f.createdAt(), Instant.now().toString())
                : f).toList();
        writeLibrary(new Library(lib.activeId(), files));
        reloadIfChanged(true);
        return metaOrNull(id);
    }

    /** 删除画布：不能删当前启用的一张，也不能删到一张不剩。 */
    public synchronized void deleteCanvas(String id) {
        ensureCanvas(id);
        if (id.equals(activeId)) throw new IllegalArgumentException("不能删除当前启用中的画布，请先启用另一张");
        if (metas.size() <= 1) throw new IllegalArgumentException("至少要保留一张画布");
        Library lib = readLibrary();
        List<CanvasFile> files = lib.canvases().stream().filter(f -> !f.id().equals(id)).toList();
        writeLibrary(new Library(lib.activeId(), files));
        deleteRecursively(flowsRoot.resolve(id));
        deleteRecursively(historyRoot.resolve(historyKind(id)));
        reloadIfChanged(true);
        log.info("画布已删除：{}", id);
    }

    /* ---------------- 历史 ---------------- */

    public Path pathOf(String id) {
        return draftPath(id);
    }

    public List<Integer> history() {
        return ConfigHistory.versions(historyRoot, historyKind(activeId));
    }

    public List<ConfigVersion> historyEntries() {
        List<ConfigVersion> out = new ArrayList<>();
        for (Integer v : history()) {
            if (out.size() >= 50) break;
            FlowDefinition s = snapshot(v);
            if (s != null) out.add(new ConfigVersion(v, s.updatedAt(), s.updatedBy()));
        }
        return out;
    }

    public FlowDefinition snapshot(int version) {
        return ConfigHistory.read(historyRoot, historyKind(activeId), version, FlowDefinition.class, mapper);
    }

    public FlowDefinition snapshot(String canvasId, int version) {
        return ConfigHistory.read(historyRoot, historyKind(canvasId), version, FlowDefinition.class, mapper);
    }

    public synchronized FlowDefinition rollback(int version, String actor) {
        FlowDefinition snapshot = snapshot(version);
        if (snapshot == null) throw new IllegalArgumentException("没有这个历史版本：v" + version);
        FlowDefinition pub = readDefinition(publishedPath(activeId));
        int nextVersion = (pub == null ? version : Math.max(pub.version(), version)) + 1;
        String who = actor == null || actor.isBlank() ? "ops" : actor.trim();
        FlowDefinition next = new FlowDefinition(nextVersion, Instant.now().toString(),
                who + " · rollback v" + version,
                snapshot.stages(), snapshot.intervention(), snapshot.messages(), snapshot.start());
        writeDefinition(draftPath(activeId), next);
        writeDefinition(publishedPath(activeId), next);
        ConfigHistory.snapshot(historyRoot, historyKind(activeId), next.version(), next, mapper);
        published = next;
        touchMeta(activeId);
        log.info("流程已回滚：画布 {} 到 v{}，生成 v{}", activeId, version, nextVersion);
        return next;
    }

    /* ---------------- 默认值 ---------------- */

    /**
     * 第一次启动时的默认值：话术取自脚本资产，其余与代码里的既有行为一致。
     *
     * 这里必须与 config/flow-params.json 保持同样的拓扑——包括行为实验与判断节点。
     */
    public FlowDefinition defaults() {
        Map<String, StageSpec> stages = new LinkedHashMap<>();
        stages.put(CbtStage.PROBLEM_IDENTIFICATION.name(), spec(CbtStage.PROBLEM_IDENTIFICATION,
                "情境", "COGNITIVE_EXTRACTION", "none", 40, 40));
        stages.put(CbtStage.COGNITIVE_EXTRACTION.name(), spec(CbtStage.COGNITIVE_EXTRACTION,
                "想法", "COGNITIVE_REVIEW", "none", 300, 40));
        stages.put(CbtStage.COGNITIVE_REVIEW.name(), spec(CbtStage.COGNITIVE_REVIEW,
                "检验", "INTERVENTION_DECISION", "cbt_balance", 560, 40));
        stages.put(CbtStage.INTERVENTION_DECISION.name(), new StageSpec(
                "行动",
                script.question(CbtStage.INTERVENTION_DECISION),
                "ACTION_CONFIRM",
                "none",
                List.of(
                        new BranchRule(ConditionWhen.INTERVENTION_BEHAVIORAL, "BEHAVIORAL_EXPERIMENT"),
                        new BranchRule(ConditionWhen.INTERVENTION_OBSERVE, "CONDITION"),
                        new BranchRule(ConditionWhen.ELSE, "ACTION_CONFIRM")),
                820, 40));
        stages.put(CbtStage.ACTION_CONFIRM.name(), spec(CbtStage.ACTION_CONFIRM,
                "确认", "ACTION_REVIEW", "none", 820, 250));
        stages.put(CbtStage.ACTION_REVIEW.name(), spec(CbtStage.ACTION_REVIEW,
                "复盘", "SESSION_SUMMARY", "cbt_summary", 560, 250));
        stages.put(CbtStage.SESSION_SUMMARY.name(), spec(CbtStage.SESSION_SUMMARY,
                "总结", null, "none", 300, 250));
        stages.put(CbtStage.SAFETY_HOLD.name(), spec(CbtStage.SAFETY_HOLD,
                "安全暂停", null, "none", 40, 250));
        stages.put(CbtStage.BEHAVIORAL_EXPERIMENT.name(), spec(CbtStage.BEHAVIORAL_EXPERIMENT,
                "行为实验", "ACTION_CONFIRM", "none", 560, 400));
        stages.put(CbtStage.CONDITION.name(), new StageSpec(
                "判断", "", null, "none",
                List.of(
                        new BranchRule(ConditionWhen.CONFIRMED, "ACTION_REVIEW"),
                        new BranchRule(ConditionWhen.ELSE, "ACTION_CONFIRM")),
                300, 400));

        Map<String, List<String>> intervention = new LinkedHashMap<>();
        intervention.put("behavioral_experiment", List.of("试", "练习", "做", "写"));
        intervention.put("cognitive_restructuring", List.of("换", "重新", "想"));
        intervention.put("self_monitoring", List.of());

        return new FlowDefinition(1, null, "system", stages, intervention);
    }

    private StageSpec spec(CbtStage stage, String title, String next, String model, int x, int y) {
        String text = script.templates().getOrDefault(stage, "");
        return new StageSpec(title, text, next, model, x, y);
    }

    /* ---------------- 初始化与文件 ---------------- */

    private void ensureLibrary() {
        try {
            Files.createDirectories(flowsRoot);
            if (Files.exists(indexPath)) {
                Library lib = readLibrary();
                if (lib != null && lib.canvases() != null && !lib.canvases().isEmpty()) {
                    activeId = lib.activeId() == null || lib.canvases().stream().noneMatch(c -> c.id().equals(lib.activeId()))
                            ? lib.canvases().get(0).id() : lib.activeId();
                    published = readDefinition(publishedPath(activeId));
                    reloadIfChanged(true);
                    if (published == null) {
                        published = defaults();
                    }
                    ensureBaseline();
                    return;
                }
            }
            migrateLegacy();
        } catch (Exception e) {
            log.warn("画布库初始化失败，使用内置默认流程：{}", e.getMessage());
            activeId = DEFAULT_CANVAS_ID;
            metas = List.of(new CanvasMeta(DEFAULT_CANVAS_ID, DEFAULT_CANVAS_NAME, "",
                    Instant.now().toString(), Instant.now().toString(), 1, 1, true));
            published = defaults();
        }
    }

    private void migrateLegacy() {
        FlowDefinition seed = readDefinition(legacyPath);
        if (seed == null) {
            seed = defaults();
        } else {
            List<String> errors = FlowValidator.validate(seed);
            if (!errors.isEmpty()) {
                log.warn("旧流程文件不合法，迁移时改用内置默认流程：{}", String.join("；", errors));
                seed = defaults();
            }
        }
        String now = Instant.now().toString();
        writeDefinition(draftPath(DEFAULT_CANVAS_ID), seed);
        writeDefinition(publishedPath(DEFAULT_CANVAS_ID), seed);
        writeLibrary(new Library(DEFAULT_CANVAS_ID,
                List.of(new CanvasFile(DEFAULT_CANVAS_ID, DEFAULT_CANVAS_NAME, "", now, now))));
        activeId = DEFAULT_CANVAS_ID;
        published = seed;
        ConfigHistory.snapshot(historyRoot, historyKind(DEFAULT_CANVAS_ID), seed.version(), seed, mapper);
        indexMtime = mtime(indexPath);
        publishedMtime = mtime(publishedPath(activeId));
        reloadIfChanged(true);
        log.info("流程画布库已初始化：旧文件 {} → 默认画布 v{}", legacyPath, seed.version());
    }

    private void ensureBaseline() {
        FlowDefinition pub = readDefinition(publishedPath(activeId));
        if (pub == null) return;
        if (!history().contains(pub.version())) {
            ConfigHistory.snapshot(historyRoot, historyKind(activeId), pub.version(), pub, mapper);
        }
    }

    private void reloadIfChanged() {
        reloadIfChanged(false);
    }

    private synchronized void reloadIfChanged(boolean force) {
        try {
            long im = mtime(indexPath);
            if (force || im != indexMtime) {
                Library lib = readLibrary();
                if (lib != null && lib.canvases() != null && !lib.canvases().isEmpty()) {
                    activeId = lib.activeId() == null ? lib.canvases().get(0).id() : lib.activeId();
                    metas = buildMetas(lib);
                    published = readDefinition(publishedPath(activeId));
                    indexMtime = im;
                    publishedMtime = mtime(publishedPath(activeId));
                }
            }
            if (published == null || force) {
                published = readDefinition(publishedPath(activeId));
            } else {
                long pm = mtime(publishedPath(activeId));
                if (pm != publishedMtime) {
                    FlowDefinition loaded = readDefinition(publishedPath(activeId));
                    if (loaded != null) {
                        published = loaded;
                        publishedMtime = pm;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("画布库热加载失败，继续使用内存中的版本：{}", e.getMessage());
        }
    }

    private List<CanvasMeta> buildMetas(Library lib) {
        List<CanvasMeta> out = new ArrayList<>();
        for (CanvasFile f : lib.canvases()) {
            FlowDefinition draft = readDefinition(draftPath(f.id()));
            FlowDefinition pub = readDefinition(publishedPath(f.id()));
            out.add(new CanvasMeta(f.id(), f.name(), f.description(), f.createdAt(), f.updatedAt(),
                    draft == null ? 0 : draft.version(), pub == null ? 0 : pub.version(),
                    f.id().equals(lib.activeId())));
        }
        return List.copyOf(out);
    }

    private void touchMeta(String id) {
        Library lib = readLibrary();
        if (lib == null) return;
        List<CanvasFile> files = lib.canvases().stream().map(f -> f.id().equals(id)
                ? new CanvasFile(f.id(), f.name(), f.description(), f.createdAt(), Instant.now().toString())
                : f).toList();
        writeLibrary(new Library(lib.activeId(), files));
        reloadIfChanged(true);
    }

    private Library readLibrary() {
        if (!Files.exists(indexPath)) return null;
        try {
            return mapper.readValue(indexPath.toFile(), Library.class);
        } catch (Exception e) {
            log.warn("画布清单读不出来：{}", e.getMessage());
            return null;
        }
    }

    private void writeLibrary(Library lib) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(indexPath.toFile(), lib);
        } catch (IOException e) {
            throw new IllegalStateException("画布清单写入失败：" + e.getMessage(), e);
        }
    }

    private void writeIndex() {
        Library lib = readLibrary();
        if (lib != null) writeLibrary(new Library(activeId, lib.canvases()));
    }

    private FlowDefinition readDefinition(Path file) {
        if (!Files.exists(file)) return null;
        try {
            return mapper.readValue(file.toFile(), FlowDefinition.class);
        } catch (Exception e) {
            log.warn("流程文件读不出来（{}）：{}", file, e.getMessage());
            return null;
        }
    }

    private void writeDefinition(Path file, FlowDefinition def) {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), def);
        } catch (IOException e) {
            throw new IllegalStateException("流程文件写入失败：" + file + "：" + e.getMessage(), e);
        }
    }

    private Path draftPath(String id) {
        return flowsRoot.resolve(safeId(id)).resolve("draft.json");
    }

    private Path publishedPath(String id) {
        return flowsRoot.resolve(safeId(id)).resolve("published.json");
    }

    private String historyKind(String id) {
        return DEFAULT_CANVAS_ID.equals(id) ? "flow" : "flow-" + safeId(id);
    }

    private void ensureCanvas(String id) {
        reloadIfChanged();
        if (id == null || id.isBlank() || metas.stream().noneMatch(m -> m.id().equals(id))) {
            throw new IllegalArgumentException("没有这张画布：" + id);
        }
    }

    private long mtime(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }

    private static String safeId(String id) {
        return id == null ? DEFAULT_CANVAS_ID : id.replaceAll("[^a-zA-Z0-9_-]", "");
    }

    private static String safeName(String name, String fallback) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) return fallback;
        return value.length() > 40 ? value.substring(0, 40) : value;
    }

    private static String who(String updatedBy) {
        return updatedBy == null || updatedBy.isBlank() ? "ops" : updatedBy.trim();
    }

    private static boolean sameContent(FlowDefinition a, FlowDefinition b) {
        return Objects.equals(a.stages(), b.stages())
                && Objects.equals(a.intervention(), b.intervention())
                && Objects.equals(a.messages(), b.messages())
                && Objects.equals(a.start(), b.start());
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 尽力删除；删不掉的文件不应阻断清单更新
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    private static FlowDefinition normalize(FlowDefinition def) {
        if (def == null) throw new IllegalArgumentException("流程定义不能为空");
        Map<String, StageSpec> stages = new LinkedHashMap<>();
        if (def.stages() != null) {
            for (Map.Entry<String, StageSpec> entry : def.stages().entrySet()) {
                StageSpec s = entry.getValue();
                if (s == null) continue;
                String id = entry.getKey() == null ? "" : entry.getKey().trim();
                stages.put(id, new StageSpec(
                        s.title() == null ? "" : s.title().trim(),
                        s.text() == null ? "" : s.text().strip(),
                        s.next() == null || s.next().isBlank() ? null : s.next().trim(),
                        s.model() == null || s.model().isBlank() ? StageSpec.MODEL_NONE : s.model().trim(),
                        s.branches(),
                        s.x(), s.y()));
            }
        }
        Map<String, List<String>> intervention = new LinkedHashMap<>();
        if (def.intervention() != null) {
            for (Map.Entry<String, List<String>> entry : def.intervention().entrySet()) {
                List<String> words = entry.getValue() == null ? List.of() : entry.getValue().stream()
                        .filter(w -> w != null && !w.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
                intervention.put(entry.getKey(), words);
            }
        }
        return new FlowDefinition(def.version(), def.updatedAt(), def.updatedBy(),
                stages, intervention, mergeMessages(def.messages()), def.start());
    }

    /** 配置里没写的系统话术用内置默认补齐，避免"少一个键就少一句话"。 */
    private static Map<String, String> mergeMessages(Map<String, String> incoming) {
        Map<String, String> out = new LinkedHashMap<>(FlowMessages.defaults());
        if (incoming != null) {
            for (Map.Entry<String, String> e : incoming.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                out.put(e.getKey().trim(), e.getValue().strip());
            }
        }
        return out;
    }
}
