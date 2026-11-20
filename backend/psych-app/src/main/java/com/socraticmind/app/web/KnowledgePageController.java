package com.socraticmind.app.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 让 /knowledge 这个短地址直接打开用户知识问答页。
 *
 * Spring Boot 只给根路径做 welcome page，子目录不会自动落到 index.html，
 * 所以这里显式重定向一次；静态资源仍由 classpath:/static 提供。
 */
@Controller
public class KnowledgePageController {

    @GetMapping({"/knowledge", "/knowledge/"})
    public String page(HttpServletRequest request) {
        String query = request.getQueryString();
        return "redirect:/knowledge/index.html" + (query == null || query.isBlank() ? "" : "?" + query);
    }
}
