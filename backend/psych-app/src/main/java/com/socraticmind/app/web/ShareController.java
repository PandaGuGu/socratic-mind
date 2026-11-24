package com.socraticmind.app.web;

import com.socraticmind.app.share.ShareService;
import com.socraticmind.session.CbtMessage;
import com.socraticmind.session.CbtSession;
import com.socraticmind.session.CbtSessionStore;
import com.socraticmind.session.CbtStage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 公开的分享读取接口：凭链接看一次，不需要登录。
 *
 * 刻意只投影"记录本身"：阶段、想法链、行动计划、情绪自评、偏差候选、消息。
 * 不带联系人电话，不带内部轨迹，不带可枚举的会话 id——拿到链接的人只需要看这一轮，
 * 不需要知道它在这套系统里叫什么、还能不能顺着找到别的。
 *
 * 已使用 / 已过期 / 已撤销一律 410，不区分，避免把"这条链接曾经存在"变成可探测信息。
 */
@RestController
@RequestMapping("/api/share")
public class ShareController {

    private final ShareService shares;
    private final CbtSessionStore sessions;

    public ShareController(ShareService shares, CbtSessionStore sessions) {
        this.shares = shares;
        this.sessions = sessions;
    }

    public record SharedSession(
            CbtStage stage,
            String stageLabel,
            String targetProblem,
            String automaticThought,
            String candidateThought,
            String userConfirmedThought,
            String interventionType,
            String actionPlan,
            String actionResult,
            String summary,
            List<Integer> moodSeries,
            List<String> distortions,
            String safetyStatus,
            Instant createdAt,
            Instant updatedAt
    ) {
        static SharedSession of(CbtSession s) {
            return new SharedSession(s.stage(), s.stage().label(), s.targetProblem(),
                    s.automaticThought(), s.candidateThought(), s.userConfirmedThought(),
                    s.interventionType(), s.actionPlan(), s.actionResult(), s.summary(),
                    s.moodPoints().stream().map(com.socraticmind.session.MoodPoint::score).toList(),
                    s.distortions(), s.safetyStatus(), s.createdAt(), s.updatedAt());
        }
    }

    public record SharedRecord(SharedSession session, List<CbtMessage> messages,
                               Instant sharedAt, Instant expiresAt) {
    }

    @GetMapping("/{token}")
    public ResponseEntity<Object> open(@PathVariable String token) {
        // 先确认会话还在，再消耗这一次；否则"会话被删"会白白烧掉一条有效链接。
        var preview = shares.preview(token);
        CbtSession session = preview.flatMap(p -> sessions.find(p.sessionId())).orElse(null);
        var opened = session == null ? java.util.Optional.<ShareService.Opened>empty() : shares.open(token);
        if (session == null || opened.isEmpty()) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("code", "SHARE_GONE", "message", "这条链接已经用过、过期或被撤回了"));
        }
        return ResponseEntity.ok(new SharedRecord(SharedSession.of(session),
                session.history(), Instant.now(), opened.get().expiresAt()));
    }
}
