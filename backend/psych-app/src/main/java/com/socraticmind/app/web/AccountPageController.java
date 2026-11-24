package com.socraticmind.app.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 让 /account 这个短地址打开账号页。
 *
 * 与知识页同一个原因：Spring Boot 只给根路径做 welcome page，
 * 子目录不会自动落到 index.html，所以显式重定向一次。
 */
@Controller
public class AccountPageController {

    @GetMapping({"/account", "/account/"})
    public String page(HttpServletRequest request) {
        String query = request.getQueryString();
        return "redirect:/account/index.html" + (query == null || query.isBlank() ? "" : "?" + query);
    }
}
