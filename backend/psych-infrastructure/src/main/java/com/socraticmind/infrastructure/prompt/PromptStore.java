package com.socraticmind.infrastructure.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.common.prompt.PromptConfig;
import com.socraticmind.common.prompt.PromptSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * 提示词与问答参数的落盘存储。
 *
 * 首次启动没有文件时，用随包提示词作为默认值；保存后写入 config/prompts.json（容器里在 appconfig 卷）。
 * 读取走内存，保存即生效，不需要重启。
 */
@Component
public class PromptStore implements PromptSettings {

    private static final Logger log = LoggerFactory.getLogger(PromptStore.class);

    private static final String FREE_FALLBACK =
            "你是一个愿意听人说话的人。一次最多问一个问题；不诊断、不用药、不承诺疗效，也不替对方下结论。";
    private static final String KNOWLEDGE_FALLBACK =
            "只根据给出的证据回答。证据不足时明确说资料不足，不要编造；引用编号必须来自证据。";

    private final Path path;
    private final ObjectMapper mapper;
    private volatile PromptConfig current;

    public PromptStore(@Value("${psych.prompts.file:config/prompts.json}") String file,
                       ObjectMapper mapper) {
        this.path = Path.of(file).toAbsolutePath().normalize();
        this.mapper = mapper;
        this.current = loadOrDefault();
    }

    @Override
    public PromptConfig get() {
        return current;
    }

    public boolean exists() {
        return Files.exists(path);
    }

    public Path path() {
        return path;
    }

    public synchronized PromptConfig save(PromptConfig incoming, String updatedBy) {
        String free = trim(incoming.freeChat());
        String knowledge = trim(incoming.knowledgeAnswer());
        if (free.isEmpty()) throw new IllegalArgumentException("门外提示词不能为空");
        if (knowledge.isEmpty()) throw new IllegalArgumentException("知识问答提示词不能为空");
        if (free.length() > 20_000) throw new IllegalArgumentException("门外提示词不能超过 2 万字");
        if (knowledge.length() > 20_000) throw new IllegalArgumentException("知识问答提示词不能超过 2 万字");
        int topK = incoming.knowledgeTopK();
        if (topK < 1 || topK > 20) throw new IllegalArgumentException("检索条数只能是 1–20");

        PromptConfig next = new PromptConfig(
                current.version() + 1, Instant.now().toString(),
                updatedBy == null || updatedBy.isBlank() ? "ops" : updatedBy,
                free, knowledge, topK);
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), next);
            current = next;
            log.info("提示词已保存：版本 {}，门外 {} 字，问答 {} 字，TopK {}，写入 {}",
                    next.version(), free.length(), knowledge.length(), topK, path);
            return next;
        } catch (IOException e) {
            throw new IllegalStateException("提示词写入失败：" + e.getMessage(), e);
        }
    }

    private PromptConfig loadOrDefault() {
        if (Files.isRegularFile(path)) {
            try {
                PromptConfig loaded = mapper.readValue(path.toFile(), PromptConfig.class);
                if (!trim(loaded.freeChat()).isEmpty() && !trim(loaded.knowledgeAnswer()).isEmpty()) {
                    return loaded;
                }
                log.warn("提示词文件存在但内容不完整，回退到随包默认值：{}", path);
            } catch (Exception e) {
                log.warn("提示词文件不可用，回退到随包默认值：{}", e.getMessage());
            }
        }
        return new PromptConfig(1, null, "system",
                classpath("/prompts/free/companion.md", FREE_FALLBACK),
                classpath("/prompts/knowledge/generate-answer.md", KNOWLEDGE_FALLBACK),
                PromptConfig.DEFAULT_TOP_K);
    }

    private String classpath(String location, String fallback) {
        try (InputStream in = new ClassPathResource(location).getInputStream()) {
            String text = stripFrontMatter(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return text.isEmpty() ? fallback : text;
        } catch (IOException e) {
            return fallback;
        }
    }

    private static String trim(String v) {
        return v == null ? "" : v.strip();
    }

    static String stripFrontMatter(String raw) {
        String text = raw.replace("\r\n", "\n");
        if (text.startsWith("---")) {
            int end = text.indexOf("\n---", 3);
            if (end > 0) return text.substring(end + 4).trim();
        }
        return text.trim();
    }
}
