package com.socraticmind.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.app.auth.AuthService;
import com.socraticmind.app.security.UserTokenService;
import com.socraticmind.app.web.support.RateLimitPort;
import com.socraticmind.app.web.support.TooManyRequestsException;
import com.socraticmind.app.web.support.UnauthorizedException;
import com.socraticmind.common.account.Account;
import com.socraticmind.session.CbtMessage;
import com.socraticmind.session.CbtSession;
import com.socraticmind.session.CbtSessionStore;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 用户自己的数据导出：账号信息 + 这个人能看到的全部会话。
 *
 * 边界：
 * - 只认 X-User-Token；设备令牌导出设备名下的记录，账号令牌导出账号名下的记录；
 * - 不提供"管理员替别人导出"的入口——那属于内部后台，且有单独审计；
 * - 导出的是用户自己的原话与系统回复，不包含内部轨迹、守卫审计或模型调试信息。
 *
 * 不登录也能导出本机记录，这是"不注册也能完整使用"的一部分。
 */
@RestController
@RequestMapping("/api/export")
public class UserExportController {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter HUMAN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final UserTokenService tokens;
    private final AuthService auth;
    private final CbtSessionStore sessions;
    private final RateLimitPort limiter;
    private final ObjectMapper mapper;

    public UserExportController(UserTokenService tokens, AuthService auth, CbtSessionStore sessions,
                                RateLimitPort limiter, ObjectMapper mapper) {
        this.tokens = tokens;
        this.auth = auth;
        this.sessions = sessions;
        this.limiter = limiter;
        this.mapper = mapper;
    }

    public record IdentityView(String kind, String userId, String accountId) {
    }

    public record AccountView(String id, String email, String displayName,
                              Instant createdAt, Instant lastLoginAt) {
        static AccountView of(Account a) {
            return new AccountView(a.id(), a.email(), a.displayName(), a.createdAt(), a.lastLoginAt());
        }
    }

    public record MessageView(String role, String stage, String content, Instant at) {
        static MessageView of(CbtMessage m) {
            return new MessageView(m.role(), m.stage() == null ? null : m.stage().name(), m.content(), m.at());
        }
    }

    public record SessionView(String id, String stage, String stageLabel, Instant createdAt, Instant updatedAt,
                              String targetProblem, String situation, String automaticThought,
                              String candidateThought, String userConfirmedThought, String interventionType,
                              String actionPlan, String actionResult, String summary,
                              String safetyStatus, String safetyReason,
                              String contactName, String contactPhone, String contactRelation,
                              List<Integer> moodSeries, List<String> emotions, List<String> distortions,
                              List<MessageView> messages) {
        static SessionView of(CbtSession s) {
            return new SessionView(s.id(), s.stage().name(), s.stage().label(), s.createdAt(), s.updatedAt(),
                    s.targetProblem(), s.situation(), s.automaticThought(), s.candidateThought(),
                    s.userConfirmedThought(), s.interventionType(), s.actionPlan(), s.actionResult(), s.summary(),
                    s.safetyStatus(), s.safetyReason(), s.contactName(), s.contactPhone(), s.contactRelation(),
                    s.moodPoints().stream().map(p -> p.score()).toList(),
                    List.copyOf(s.emotions()), List.copyOf(s.distortions()),
                    s.history().stream().map(MessageView::of).toList());
        }
    }

    public record Bundle(String note, Instant exportedAt, IdentityView identity, AccountView account,
                         List<SessionView> sessions) {
    }

    @GetMapping("/me")
    public ResponseEntity<byte[]> export(
            @RequestHeader(value = "X-User-Token", required = false) String token,
            @RequestParam(defaultValue = "json") String format) throws Exception {
        UserTokenService.Identity who = tokens.verifyIdentity(token)
                .orElseThrow(() -> new UnauthorizedException("缺少或无效的身份令牌"));
        if (!limiter.allow("export:" + who.userId(), 5, Duration.ofMinutes(1))) {
            throw new TooManyRequestsException("导出太频繁，请一分钟后再试");
        }

        Account account = who.hasAccount()
                ? auth.current(who).orElseThrow(() -> new UnauthorizedException("登录状态已失效，请重新登录"))
                : null;
        List<CbtSession> visible = new ArrayList<>(sessions.findVisible(who.userId(), who.accountId()));
        visible.sort(Comparator.comparing(CbtSession::createdAt));

        IdentityView identity = new IdentityView(
                account == null ? "device" : "account", who.userId(), who.accountId());
        List<SessionView> rows = visible.stream().map(SessionView::of).toList();
        String note = "这是你自己的数据副本：账号信息、每一轮对话与情绪自评。"
                + "不包含内部审核轨迹，也不是临床诊断或病历。";

        boolean markdown = "md".equalsIgnoreCase(format) || "markdown".equalsIgnoreCase(format);
        byte[] body;
        String extension;
        MediaType type;
        if (markdown) {
            body = toMarkdown(identity, account, rows, note).getBytes(StandardCharsets.UTF_8);
            extension = "md";
            type = new MediaType("text", "markdown", StandardCharsets.UTF_8);
        } else {
            Bundle bundle = new Bundle(note, Instant.now(), identity,
                    account == null ? null : AccountView.of(account), rows);
            body = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(bundle);
            extension = "json";
            type = MediaType.APPLICATION_JSON;
        }

        String filename = "socratic-mind-export-" + FILE_STAMP.format(Instant.now()) + "." + extension;
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(body);
    }

    private static String toMarkdown(IdentityView identity, Account account,
                                     List<SessionView> rows, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Socratic Mind 数据导出\n\n");
        sb.append("导出时间：").append(HUMAN.format(Instant.now())).append("\n\n");
        sb.append("> ").append(note).append("\n\n");

        sb.append("## 账号\n\n");
        if (account == null) {
            sb.append("- 类型：本机匿名身份（未注册）\n");
            sb.append("- 设备 id：`").append(identity.userId()).append("`\n");
        } else {
            sb.append("- 类型：注册账号\n");
            sb.append("- 邮箱：").append(nullSafe(account.email())).append("\n");
            sb.append("- 称呼：").append(nullSafe(account.displayName())).append("\n");
            sb.append("- 注册于：").append(human(account.createdAt())).append("\n");
            sb.append("- 上次登录：").append(human(account.lastLoginAt())).append("\n");
        }
        sb.append("\n共 ").append(rows.size()).append(" 轮记录。\n");

        for (SessionView s : rows) {
            sb.append("\n---\n\n");
            sb.append("## ").append(human(s.createdAt())).append(" · ").append(s.stageLabel()).append("\n\n");
            if (notBlank(s.targetProblem())) sb.append("- 情境：").append(s.targetProblem()).append("\n");
            if (notBlank(s.automaticThought())) sb.append("- 当时想法：").append(s.automaticThought()).append("\n");
            if (notBlank(s.candidateThought())) sb.append("- 候选说法：").append(s.candidateThought()).append("\n");
            if (notBlank(s.userConfirmedThought())) sb.append("- 你确认的说法：").append(s.userConfirmedThought()).append("\n");
            if (notBlank(s.actionPlan())) sb.append("- 行动计划：").append(s.actionPlan()).append("\n");
            if (notBlank(s.actionResult())) sb.append("- 复盘：").append(s.actionResult()).append("\n");
            if (notBlank(s.summary())) sb.append("- 本轮总结：").append(s.summary()).append("\n");
            if (notBlank(s.safetyStatus()) && !"normal".equals(s.safetyStatus())) {
                sb.append("- 安全状态：").append(s.safetyStatus())
                        .append(notBlank(s.safetyReason()) ? "（" + s.safetyReason() + "）" : "").append("\n");
            }
            if (!s.moodSeries().isEmpty()) sb.append("- 情绪自评：").append(s.moodSeries()).append("\n");
            if (!s.emotions().isEmpty()) sb.append("- 情绪词：").append(String.join("、", s.emotions())).append("\n");

            sb.append("\n### 对话\n\n");
            for (MessageView m : s.messages()) {
                sb.append("**").append("user".equals(m.role()) ? "我" : "系统")
                        .append("**（").append(human(m.at())).append("）：\n\n")
                        .append(m.content()).append("\n\n");
            }
        }
        sb.append("\n---\n\n导出结束。\n");
        return sb.toString();
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    private static String human(Instant at) {
        return at == null ? "—" : HUMAN.format(at);
    }

    private static String nullSafe(Object v) {
        return v == null ? "—" : String.valueOf(v);
    }
}
