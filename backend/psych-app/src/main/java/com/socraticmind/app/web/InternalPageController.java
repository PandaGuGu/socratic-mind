package com.socraticmind.app.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 让 /internal 这个短地址能直接打开控制台。
 *
 * Spring Boot 只给根路径做 welcome page，子目录不会自动落到 index.html，
 * 所以这里显式重定向一次；静态资源本身仍由 classpath:/static 提供，不经过模板引擎。
 *
 * 查询串必须手动透传：redirect: 前缀默认不传播 query，
 * 否则 ?session=&tab= 这类深链会在跳转时被丢掉。
 */
@Controller
@ConditionalOnProperty(name = "psych.internal.enabled", havingValue = "true")
public class InternalPageController {

    @GetMapping({"/internal", "/internal/"})
    public String page(HttpServletRequest request) {
        String query = request.getQueryString();
        return "redirect:/internal/index.html" + (query == null || query.isBlank() ? "" : "?" + query);
    }
}
