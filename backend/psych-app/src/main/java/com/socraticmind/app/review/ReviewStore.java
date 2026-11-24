package com.socraticmind.app.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 审核记录：谁在什么时候看过哪一版规则/词库，结论是什么。
 *
 * 它解决的是"未经临床审核"这句话无法落地的问题：
 * 系统不能替人签字，但能保证"没签过"这件事是显式的、写在页面上的、跟着版本走的。
 * 规则或词库一旦改版，旧审核自动变成"版本已变，需重审"——不会拿三周前的签字给今天的规则背书。
 *
 * 存法是一份 JSON，与策略/流程/词库同一套约定：文件即真值，坏了不生效（保留内存上一版）。
 */
@Component
public class ReviewStore {

    private static final Logger log = LoggerFactory.getLogger(ReviewStore.class);
    private static final Set<String> KINDS = Set.of("safety", "lexicon");
    private static final Set<String> STATUSES = Set.of("approved", "changes_requested");

    public record Review(String kind, int version, String status, String reviewer,
                         String reviewedAt, String note) {
    }

    private final Path path;
    private final ObjectMapper mapper;
    private volatile Map<String, Review> current = Map.of();

    public ReviewStore(@Value("${psych.review.file:config/reviews.json}") String file,
                       ObjectMapper mapper) {
        this.path = Path.of(file).toAbsolutePath().normalize();
        this.mapper = mapper;
        load();
    }

    public Path path() {
        return path;
    }

    public Map<String, Review> all() {
        return current;
    }

    public Review find(String kind) {
        return current.get(kind);
    }

    /**
     * 记录一次审核。version 由调用方传入"当前生效版本"——
     * 写进来的永远是"这一版被看过"，而不是"这个模块被看过"。
     */
    public synchronized Review save(String kind, int version, String status, String reviewer, String note) {
        if (kind == null || !KINDS.contains(kind)) {
            throw new IllegalArgumentException("kind 只能是 safety / lexicon");
        }
        if (status == null || !STATUSES.contains(status)) {
            throw new IllegalArgumentException("status 只能是 approved / changes_requested");
        }
        if (reviewer == null || reviewer.isBlank() || reviewer.strip().length() > 32) {
            throw new IllegalArgumentException("审核人必填，最多 32 字");
        }
        if (note != null && note.length() > 1000) {
            throw new IllegalArgumentException("备注最多 1000 字");
        }
        if (version < 0) {
            throw new IllegalArgumentException("版本号不能为负");
        }
        Review review = new Review(kind, version, status, reviewer.strip(),
                Instant.now().toString(), note == null ? null : note.strip());
        Map<String, Review> next = new LinkedHashMap<>(current);
        next.put(kind, review);
        write(next);
        current = Map.copyOf(next);
        log.info("审核记录已保存：{} v{} {} by {}", kind, version, status, review.reviewer());
        return review;
    }

    private void load() {
        if (!Files.exists(path)) return;
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            Map<String, Review> loaded = mapper.readValue(json, new TypeReference<>() {
            });
            current = Map.copyOf(loaded);
        } catch (Exception e) {
            // 坏文件不生效：保持空表（= 全部未审核），比"看起来审过了"安全。
            log.error("审核记录文件不可用，按未审核处理：{}", e.getMessage());
        }
    }

    private void write(Map<String, Review> reviews) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), reviews);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("审核记录写入失败：" + e.getMessage(), e);
        }
    }
}
