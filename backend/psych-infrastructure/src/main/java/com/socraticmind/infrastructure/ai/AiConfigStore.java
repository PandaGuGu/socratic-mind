package com.socraticmind.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.common.port.ChatPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型接入的读写与热切换。
 *
 * 文件即真值：后台保存到 config/ai-config.json，重启后仍按它装配；
 * 文件里有密钥，所以只在本机/受控卷里保存，不返回给页面。
 */
@Component
public class AiConfigStore {

    private static final Logger log = LoggerFactory.getLogger(AiConfigStore.class);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    private final Path path;
    private final ObjectMapper mapper;

    private volatile AiProperties defaults = new AiProperties();
    private volatile AiRuntimeConfig current;
    private volatile SwitchableChatPort port;

    public AiConfigStore(@Value("${psych.ai.config-file:config/ai-config.json}") String file,
                         ObjectMapper mapper) {
        this.path = Path.of(file).toAbsolutePath().normalize();
        this.mapper = mapper;
        this.current = AiRuntimeConfig.from(defaults);
    }

    /** 由 AiConfig 在装配聊天端口时调用：接上开关，并套用已经保存的配置。 */
    public synchronized void bind(SwitchableChatPort switchable, AiProperties props) {
        this.port = switchable;
        this.defaults = props;
        AiRuntimeConfig saved = read();
        if (saved == null) {
            this.current = AiRuntimeConfig.from(props);
            return;
        }
        try {
            saved.requireComplete();
            switchable.switchTo(build(saved));
            this.current = saved;
            log.info("模型接入已从 {} 载入：{}", path, describe(saved));
        } catch (RuntimeException e) {
            this.current = AiRuntimeConfig.from(props);
            log.warn("保存的模型配置不可用，继续用启动参数：{}", e.getMessage());
        }
    }

    public AiRuntimeConfig current() {
        return current;
    }

    public View view() {
        AiRuntimeConfig c = current;
        return new View(c.mode(), c.baseUrl(), c.chatModel(), c.temperature(), c.noThink(),
                c.thinkingDisabled(), c.readTimeoutSeconds(), c.hasApiKey(),
                c.hasApiKey() ? "已保存（不显示）" : "",
                port == null ? "未装配" : port.modelId(),
                Files.exists(path) ? "file" : "startup",
                path.toString());
    }

    /** 保存并立即生效。apiKey 留空表示沿用已保存的密钥。 */
    public synchronized View save(AiRuntimeConfig incoming) {
        if (incoming == null) throw new IllegalArgumentException("配置不能为空");
        AiRuntimeConfig next = incoming.withFallbackKey(current.apiKey());
        next.requireComplete();
        if (port == null) throw new IllegalStateException("模型端口还没装配好");
        ChatPort built = build(next);
        write(next);
        port.switchTo(built);
        current = next;
        log.info("模型接入已切换：{}", describe(next));
        return view();
    }

    /** 试一条最小请求，不落盘、不切换。 */
    public TestResult test(AiRuntimeConfig candidate) {
        AiRuntimeConfig next = candidate == null ? current : candidate.withFallbackKey(current.apiKey());
        next.requireComplete();
        long t0 = System.nanoTime();
        try {
            ChatPort probe = build(next);
            String reply = probe.chat("你是一个连通性测试助手。", "只回复 OK 两个字母，不要解释。");
            long ms = Math.round((System.nanoTime() - t0) / 1_000_000.0);
            return new TestResult(true, ms, trim(reply), "");
        } catch (RuntimeException e) {
            long ms = Math.round((System.nanoTime() - t0) / 1_000_000.0);
            return new TestResult(false, ms, "", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /* ---------------- 本地模型 ---------------- */

    public LocalStatus localStatus(String baseUrl) {
        String base = baseUrl == null || baseUrl.isBlank() ? AiRuntimeConfig.LOCAL_BASE_URL : baseUrl.trim();
        String root = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(root + "/models"))
                    .timeout(Duration.ofSeconds(4))
                    .header("Authorization", "Bearer ollama")
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                return new LocalStatus(false, root, List.of(), "HTTP " + res.statusCode());
            }
            JsonNode data = mapper.readTree(res.body()).path("data");
            List<String> models = new ArrayList<>();
            data.forEach(n -> {
                String id = n.path("id").asText("");
                if (!id.isBlank()) models.add(id);
            });
            return new LocalStatus(true, root, models, "");
        } catch (Exception e) {
            return new LocalStatus(false, root, List.of(), e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * 尝试拉起本机 Ollama。容器里通常没有 ollama 命令，失败时把原因原样返回，
     * 而不是假装"启动成功"。
     */
    public LocalStatus localStart(String baseUrl) {
        LocalStatus before = localStatus(baseUrl);
        if (before.running()) return before;
        try {
            ProcessBuilder pb = new ProcessBuilder("ollama", "serve");
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.start();
        } catch (IOException e) {
            return new LocalStatus(false, before.baseUrl(), List.of(),
                    "没找到 ollama 命令（" + e.getMessage() + "）。请先安装 Ollama，或在宿主机运行 ollama serve。");
        }
        for (int i = 0; i < 12; i++) {
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            LocalStatus now = localStatus(baseUrl);
            if (now.running()) return now;
        }
        return new LocalStatus(false, before.baseUrl(), List.of(), "已尝试启动，但 8 秒内没有连上 Ollama");
    }

    /* ---------------- 内部 ---------------- */

    private ChatPort build(AiRuntimeConfig c) {
        if (AiRuntimeConfig.STUB.equals(c.mode())) {
            return new StubChatAdapter();
        }
        return new HttpChatAdapter(c.baseUrl(), c.apiKey(), c.chatModel(), c.temperature(),
                c.thinkingDisabled(), c.noThink(), c.readTimeoutSeconds());
    }

    private AiRuntimeConfig read() {
        if (!Files.exists(path)) return null;
        try {
            return mapper.readValue(path.toFile(), AiRuntimeConfig.class);
        } catch (Exception e) {
            log.warn("模型配置文件读不出来（{}）：{}", path, e.getMessage());
            return null;
        }
    }

    private void write(AiRuntimeConfig config) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), config);
        } catch (IOException e) {
            throw new IllegalStateException("模型配置写入失败：" + e.getMessage(), e);
        }
    }

    private static String describe(AiRuntimeConfig c) {
        return c.mode() + " · " + (c.chatModel().isBlank() ? "stub" : c.chatModel()) + " · " + c.baseUrl();
    }

    private static String trim(String value) {
        if (value == null) return "";
        String one = value.replaceAll("\\s+", " ").trim();
        return one.length() > 160 ? one.substring(0, 160) + "…" : one;
    }

    public record View(String mode, String baseUrl, String chatModel, double temperature,
                       boolean noThink, boolean thinkingDisabled, int readTimeoutSeconds,
                       boolean apiKeySet, String apiKeyHint, String modelId, String source, String path) {
    }

    public record TestResult(boolean ok, long ms, String reply, String error) {
    }

    public record LocalStatus(boolean running, String baseUrl, List<String> models, String error) {
    }
}
