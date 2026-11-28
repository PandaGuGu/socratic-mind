package com.socraticmind.app.web;

import com.socraticmind.infrastructure.ai.AiConfigStore;
import com.socraticmind.infrastructure.ai.AiRuntimeConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.socraticmind.app.security.RequirePerm;
import com.socraticmind.common.model.Operator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型接入配置：云端 Key、本地 Ollama、连通性测试。
 *
 * 与只读的模型仪表盘分开：这里会改变运行中的模型，必须由内部令牌保护，
 * 且返回给页面的配置永远不带密钥明文。
 */
@RestController
@RequestMapping("/api/internal/ai")
@ConditionalOnProperty(name = "psych.internal.enabled", havingValue = "true")
public class InternalAiController {

    private final AiConfigStore store;

    public InternalAiController(AiConfigStore store) {
        this.store = store;
    }

    public record SaveRequest(AiRuntimeConfig config) {
    }

    @GetMapping("/config")
    public AiConfigStore.View config() {
        return store.view();
    }

    @PutMapping("/config")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public AiConfigStore.View save(@RequestBody SaveRequest request) {
        if (request == null || request.config() == null) {
            throw new IllegalArgumentException("配置不能为空");
        }
        return store.save(request.config());
    }

    /** 用候选配置试一条最小请求；不保存、不切换。 */
    @PostMapping("/config/test")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public AiConfigStore.TestResult test(@RequestBody(required = false) SaveRequest request) {
        return store.test(request == null ? null : request.config());
    }

    @GetMapping("/local/status")
    public AiConfigStore.LocalStatus localStatus(@RequestParam(required = false) String baseUrl) {
        return store.localStatus(baseUrl);
    }

    /** 尝试启动本机 Ollama；容器档没有 ollama 命令时会如实返回原因。 */
    @PostMapping("/local/start")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public AiConfigStore.LocalStatus localStart(@RequestParam(required = false) String baseUrl) {
        return store.localStart(baseUrl);
    }
}
