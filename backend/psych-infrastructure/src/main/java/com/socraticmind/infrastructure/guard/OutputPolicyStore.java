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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final ObjectMapper mapper;

    private volatile OutputPolicy current = OutputPolicy.defaults();
    private volatile long seenMtime = -1L;

    public OutputPolicyStore(@Value("${psych.guard.policy-file:config/output-policy.json}") String file,
                             ObjectMapper mapper) {
        this.path = Path.of(file).toAbsolutePath().normalize();
        this.mapper = mapper;
        reloadIfChanged();
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

    public synchronized OutputPolicy save(OutputPolicy incoming, String updatedBy) {
        OutputPolicy normalized = normalize(incoming);
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
            current = loaded;
            seenMtime = mtime;
            log.info("输出策略已热加载：版本 {}，档位 {}", loaded.version(), loaded.profiles().keySet());
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
