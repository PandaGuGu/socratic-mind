package com.socraticmind.infrastructure.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.common.guard.OutputPolicy;
import com.socraticmind.common.guard.ProfileRule;
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
import java.util.stream.Stream;

/**
 * 输出策略的存储：一份 JSON 文件，运维改完保存即生效，不需要重启。
 *
 * 三个刻意的选择：
 * 1. **文件即真值**，不是数据库——策略要能进 git、能被人 review、能一键回滚；
 * 2. **热加载按 mtime**，只在文件真的变了才重读，不是每次调用都读盘；
 * 3. **坏文件不生效**，解析或校验失败时保留内存中的上一版并告警，避免一条手滑把全部档位关掉。
 */
@Component
public class OutputPolicyStore {

    private static final Logger log = LoggerFactory.getLogger(OutputPolicyStore.class);

    private final Path path;
    private final Path historyRoot;
    private final ObjectMapper mapper;

    private volatile OutputPolicy current = OutputPolicy.defaults();
    private volatile long seenMtime = -1L;

    @org.springframework.beans.factory.annotation.Autowired
    public OutputPolicyStore(@Value("${psych.guard.policy-file:config/output-policy.json}") String file,
                             @Value("${psych.config.history-dir:config/history}") String historyDir,
                             ObjectMapper mapper) {
        this.path = Path.of(file).toAbsolutePath().normalize();
        this.historyRoot = Path.of(historyDir).toAbsolutePath().normalize();
        this.mapper = mapper;
        reloadIfChanged();
        ensureBaseline();
    }

    /** 测试/脚本用：历史目录取策略文件同级。 */
    public OutputPolicyStore(String file, ObjectMapper mapper) {
        this(file, siblingHistory(file), mapper);
    }

    private static String siblingHistory(String file) {
        Path parent = Path.of(file).toAbsolutePath().normalize().getParent();
        return (parent == null ? Path.of("config") : parent).resolve("history").toString();
    }

    public OutputPolicy get() {
        reloadIfChanged();
        return current;
    }

    public Path path() {
        return path;
    }

    public boolean exists() {
        return Files.exists(path);
    }

    /** 审核页做版本对比用；与 safety/flow 一样按版本留存快照。 */
    public OutputPolicy snapshot(int version) {
        Path file = historyRoot.resolve("policy").resolve("v" + version + ".json");
        if (!Files.isRegularFile(file)) return null;
        try {
            return mapper.readValue(file.toFile(), OutputPolicy.class);
        } catch (IOException e) {
            log.warn("输出策略快照读不出来（v{}）：{}", version, e.getMessage());
            return null;
        }
    }

    public List<Integer> history() {
        Path dir = historyRoot.resolve("policy");
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            List<Integer> out = new ArrayList<>();
            files.forEach(f -> {
                String name = f.getFileName().toString();
                if (!name.startsWith("v") || !name.endsWith(".json")) return;
                try {
                    out.add(Integer.parseInt(name.substring(1, name.length() - 5)));
                } catch (NumberFormatException ignored) {
                    // 非版本文件忽略
                }
            });
            out.sort(Comparator.reverseOrder());
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    private void ensureBaseline() {
        if (!exists()) return;
        try {
            if (!history().contains(current.version())) snapshot(current);
        } catch (RuntimeException e) {
            log.warn("输出策略基线快照写入失败（不影响运行）：{}", e.getMessage());
        }
    }

    private void snapshot(OutputPolicy policy) {
        try {
            Path dir = historyRoot.resolve("policy");
            Files.createDirectories(dir);
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(dir.resolve("v" + policy.version() + ".json").toFile(), policy);
        } catch (IOException e) {
            log.warn("输出策略快照写入失败（不影响保存）：{}", e.getMessage());
        }
    }

    public synchronized OutputPolicy save(OutputPolicy incoming, String updatedBy) {
        OutputPolicy normalized = withBuiltInProfiles(normalize(incoming));
        validate(normalized);
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            OutputPolicy next = new OutputPolicy(
                    normalized.version() + 1,
                    Instant.now().toString(),
                    updatedBy == null || updatedBy.isBlank() ? "ops" : updatedBy,
                    normalized.profiles());
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), next);
            current = next;
            seenMtime = Files.getLastModifiedTime(path).toMillis();
            snapshot(next);
            log.info("输出策略已保存：版本 {}，档位 {}，写入 {}", next.version(), next.profiles().keySet(), path);
            return next;
        } catch (IOException e) {
            throw new IllegalStateException("策略文件写入失败：" + e.getMessage(), e);
        }
    }

    private synchronized void reloadIfChanged() {
        if (!Files.exists(path)) return;
        try {
            long mtime = Files.getLastModifiedTime(path).toMillis();
            if (mtime == seenMtime) return;
            OutputPolicy loaded = mapper.readValue(path.toFile(), OutputPolicy.class);
            validate(loaded);
            current = withBuiltInProfiles(loaded);
            seenMtime = mtime;
            log.info("输出策略已热加载：版本 {}，档位 {}", current.version(), current.profiles().keySet());
        } catch (Exception e) {
            // 记住 mtime，避免坏文件被每次调用重复解析刷日志；文件再改动时会重新尝试。
            seenMtime = lastMtimeOrNow();
            log.warn("输出策略文件不可用，继续使用内存中的版本 {}：{}", current.version(), e.getMessage());
        }
    }

    private long lastMtimeOrNow() {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return System.currentTimeMillis();
        }
    }

    private static OutputPolicy normalize(OutputPolicy policy) {
        if (policy == null) throw new IllegalArgumentException("策略不能为空");
        Map<String, ProfileRule> profiles = new LinkedHashMap<>();
        if (policy.profiles() != null) {
            for (Map.Entry<String, ProfileRule> entry : policy.profiles().entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().trim();
                if (key.isEmpty()) throw new IllegalArgumentException("档位名不能为空");
                ProfileRule rule = entry.getValue();
                if (rule == null) throw new IllegalArgumentException("档位 " + key + " 的规则不能为空");
                List<String> banned = rule.banned().stream()
                        .filter(b -> b != null && !b.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
                profiles.put(key, new ProfileRule(rule.enabled(), rule.minChars(), rule.maxChars(),
                        banned, rule.novelty(), rule.repairAttempts(), rule.note()));
            }
        }
        return new OutputPolicy(policy.version(), policy.updatedAt(), policy.updatedBy(), profiles);
    }

    /**
     * 老配置文件缺少内置档位时补上。
     *
     * 卷里可能是上一版镜像留下的策略文件；新增的档位（如门外 free_chat）若只写进默认值，
     * 升级后它会因为"文件里没有"而静默不设防——这正是最难发现的一类失效。
     */
    private static OutputPolicy withBuiltInProfiles(OutputPolicy policy) {
        Map<String, ProfileRule> merged = new LinkedHashMap<>(policy.profiles());
        for (Map.Entry<String, ProfileRule> entry : OutputPolicy.defaults().profiles().entrySet()) {
            merged.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return new OutputPolicy(policy.version(), policy.updatedAt(), policy.updatedBy(), merged);
    }

    private static void validate(OutputPolicy policy) {
        for (Map.Entry<String, ProfileRule> entry : policy.profiles().entrySet()) {
            String key = entry.getKey();
            ProfileRule rule = entry.getValue();
            if (rule.minChars() < 0) throw new IllegalArgumentException(key + "：最短字数不能为负");
            if (rule.maxChars() < 0) throw new IllegalArgumentException(key + "：最长字数不能为负");
            if (rule.maxChars() > 0 && rule.minChars() > rule.maxChars()) {
                throw new IllegalArgumentException(key + "：最短字数不能大于最长字数");
            }
            if (rule.novelty() < 0 || rule.novelty() > 1) {
                throw new IllegalArgumentException(key + "：重合度阈值必须在 0 与 1 之间");
            }
            if (rule.repairAttempts() < 0 || rule.repairAttempts() > 3) {
                throw new IllegalArgumentException(key + "：重试次数只能是 0–3");
            }
        }
    }
}
