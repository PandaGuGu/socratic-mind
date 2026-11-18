package com.socraticmind.app.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 产品前台的入口：/ 与 /room 都落到房间页面。
 *
 * 房间页面与内部控制台是两个物种：前台对用户说话（房间、纸片、石子），
 * 后台对运维说话（轨迹、策略、画布）。它们共用同一套 /api/cbt 接口，
 * 但视觉与导航互不引用——避免"工具感"漏进产品，也避免产品动画拖累后台。
 *
 * 查询串必须手动透传：redirect: 前缀默认不传播 query，
 * 否则 ?demo=1 这类演示入口会在跳转时被丢掉。
 */
@Controller
public class RoomPageController {

    @GetMapping({"/", "/room", "/room/"})
    public String room(HttpServletRequest request) {
        String query = request.getQueryString();
        return "redirect:/room/index.html" + (query == null || query.isBlank() ? "" : "?" + query);
    }
}
