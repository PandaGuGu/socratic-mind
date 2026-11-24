package com.socraticmind.infrastructure.store;

import com.socraticmind.common.port.EmbeddingPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 向量维度迁移：把已有的 kb_chunk.embedding 从旧维度（例如 256 的哈希桩）
 * 换成当前 embedding 模型的实际维度（BGE-M3 是 1024）。
 *
 * 为什么不自动做：ALTER 列会丢数据，重嵌入要调模型。启动时静默改库是最危险的行为。
 * 所以默认只报错并给出两条路：显式打开 {@code psych.ai.vector-migrate=true}，
 * 或者按文档清库重来。打开后也要先确认 Ollama 可用——重嵌入失败会让检索整段不可用。
 *
 * 顺序：建表（0）→ 迁移（1）→ 种子语料（100）。迁移必须早于灌语料，
 * 否则新向量会往旧维度的列里写，报一个看不出根因的错。
 */
@Component
@Order(1)
@ConditionalOnProperty(name = "psych.store", havingValue = "postgres")
public class VectorDimensionMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorDimensionMigrator.class);

    private final JdbcTemplate jdbc;
    private final EmbeddingPort embedding;
    private final int target;
    private final boolean migrate;

    public VectorDimensionMigrator(JdbcTemplate jdbc, EmbeddingPort embedding,
                                   @Value("${psych.ai.embedding-dimension:256}") int target,
                                   @Value("${psych.ai.vector-migrate:false}") boolean migrate) {
        this.jdbc = jdbc;
        this.embedding = embedding;
        this.target = target;
        this.migrate = migrate;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        String type;
        try {
            type = jdbc.queryForObject("""
                    SELECT format_type(atttypid, atttypmod)
                    FROM pg_attribute
                    WHERE attrelid = 'kb_chunk'::regclass AND attname = 'embedding'
                    """, String.class);
        } catch (Exception e) {
            log.warn("读不到 kb_chunk.embedding 的维度，跳过向量迁移：{}", e.getMessage());
            return;
        }
        int current = parseDimension(type);
        if (current <= 0) {
            log.warn("无法解析向量列类型（{}），跳过迁移", type);
            return;
        }
        if (current == target) {
            log.info("向量维度一致：{} 维", target);
            return;
        }

        if (!migrate) {
            log.error("向量维度不一致：库是 {} 维，配置是 {} 维。"
                    + "两种处理：设置 psych.ai.vector-migrate=true 让启动时重建列并重嵌入，"
                    + "或清空 kb_chunk 后重建（Docker 档 down -v）。当前不会改动数据。",
                    current, target);
            return;
        }

        log.warn("开始向量迁移：{} → {} 维。先删列与 HNSW 索引，再从 content 重嵌入。", current, target);
        jdbc.execute("DROP INDEX IF EXISTS kb_chunk_hnsw");
        jdbc.execute("ALTER TABLE kb_chunk DROP COLUMN IF EXISTS embedding");
        jdbc.execute("ALTER TABLE kb_chunk ADD COLUMN embedding vector(" + target + ")");
        jdbc.execute("CREATE INDEX IF NOT EXISTS kb_chunk_hnsw ON kb_chunk USING hnsw (embedding vector_cosine_ops)");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, content FROM kb_chunk WHERE embedding IS NULL AND is_parent = false");
        int done = 0;
        for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.get("id"));
            String content = String.valueOf(row.get("content"));
            float[] vector = embedding.embed(content);
            if (vector == null || vector.length != target) {
                throw new IllegalStateException("重嵌入维度不符：期望 " + target
                        + "，实际 " + (vector == null ? 0 : vector.length) + "（chunk " + id + "）");
            }
            jdbc.update("UPDATE kb_chunk SET embedding = ?::vector WHERE id = ?", toVector(vector), id);
            done++;
        }
        log.info("向量迁移完成：{} 条 chunk 已重嵌入（{} 维）", done, target);
    }

    private static int parseDimension(String type) {
        if (type == null) return -1;
        int open = type.indexOf('(');
        int close = type.indexOf(')');
        if (open < 0 || close <= open) return -1;
        try {
            return Integer.parseInt(type.substring(open + 1, close).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String toVector(float[] values) {
        StringBuilder sb = new StringBuilder(values.length * 8).append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }
}
