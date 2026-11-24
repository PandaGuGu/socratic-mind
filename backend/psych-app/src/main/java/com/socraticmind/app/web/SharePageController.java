package com.socraticmind.app.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 让 /share/?t=... 能直接打开。Spring Boot 只为根路径做 welcome page，
 * 子目录不会自动落到 index.html；这里显式重定向一次，并保留查询串（否则 token 会丢）。
 *
 * 这个入口是公开的：拿到链接的人不需要登录。是否有效由 /api/share/{token} 决定。
 */
@Controller
public class SharePageController {

    @GetMapping({"/share", "/share/"})
    public String page(HttpServletRequest request) {
        String query = request.getQueryString();
        return "redirect:/share/index.html" + (query == null || query.isBlank() ? "" : "?" + query);
    }
}
