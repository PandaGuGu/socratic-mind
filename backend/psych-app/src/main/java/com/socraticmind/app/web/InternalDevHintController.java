package com.socraticmind.app.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 本机开发档的"钥匙提示"。
 *
 * 背景：内部接口要求令牌（ADR-0007），但开发档默认令牌就是 dev-internal-token，
 * 让人先去翻配置文件、再手打进弹窗，是纯摩擦——尤其在做验收的时候。
 *
 * 安全边界：
 * 1. 只有显式 {@code psych.internal.dev-hint=true} 才注册，默认关闭；
 * 2. 只有令牌**仍是开发档默认值**时才返回它；换过令牌就返回空，不做任何提示；
 * 3. 路径是 {@code /api/internal-hint}，故意不在 {@code /api/internal/**} 的鉴权范围内——
 *    否则它自己也要钥匙，就没法当提示了。
 *
 * 换句话说：正式环境把 dev-hint 关掉，这个接口连注册都不会发生。
 */
@RestController
@ConditionalOnProperty(name = "psych.internal.dev-hint", havingValue = "true")
public class InternalDevHintController {

    private static final Logger log = LoggerFactory.getLogger(InternalDevHintController.class);
    private static final String DEV_DEFAULT = "dev-internal-token";

    private final String token;
    private final com.socraticmind.app.security.DevOperatorToken operatorToken;

    public InternalDevHintController(@Value("${psych.internal.token:}") String token,
                                     com.socraticmind.app.security.DevOperatorToken operatorToken) {
        this.token = token == null ? "" : token.trim();
        this.operatorToken = operatorToken;
        if (DEV_DEFAULT.equals(this.token)) {
            log.info("本机开发档：内部令牌为「{}」，/internal 会自动填入。正式环境请关闭 psych.internal.dev-hint。",
                    DEV_DEFAULT);
        } else {
            log.info("内部令牌已自定义，开发档不再自动提示；请在 /internal 首次访问时手动输入。");
        }
    }

    @GetMapping("/api/internal-hint")
    public Map<String, String> hint() {
        if (!DEV_DEFAULT.equals(token)) return Map.of();
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        out.put("token", token);
        out.put("note", "本机开发档默认令牌，已自动填入；正式环境请关闭 psych.internal.dev-hint");
        // 组织模式下写操作还需要操作者令牌；开发档一并给出，否则每个页面第一次操作都是 401。
        if (operatorToken.get() != null) {
            out.put("operatorToken", operatorToken.get());
        }
        return out;
    }
}
