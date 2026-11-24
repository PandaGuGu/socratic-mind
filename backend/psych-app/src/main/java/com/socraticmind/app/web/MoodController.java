package com.socraticmind.app.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跨会话情绪趋势。单独的路径，单独的文件，但实现只有一份——直接转给
 * {@link CbtController#moodTrend}，不复制"令牌 + 账号存在 + 可见范围"那套判断。
 *
 * 为什么不用 /api/cbt/sessions/mood/trend：趋势不是某一条会话的属性，
 * 把它挂在会话集合下面会让读者以为"先选会话再看趋势"。
 */
@RestController
@RequestMapping("/api/cbt/mood")
public class MoodController {

    private final CbtController cbt;

    public MoodController(CbtController cbt) {
        this.cbt = cbt;
    }

    @GetMapping("/trend")
    public CbtController.MoodTrend trend(
            @RequestHeader(value = CbtController.USER_TOKEN_HEADER, required = false) String token,
            @RequestParam(defaultValue = "30") int days) {
        return cbt.moodTrend(token, days);
    }
}
