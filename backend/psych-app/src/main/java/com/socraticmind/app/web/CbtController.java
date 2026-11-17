package com.socraticmind.app.web;

import com.socraticmind.agent.cbt.CbtFlow;
import com.socraticmind.agent.cbt.TurnResult;
import com.socraticmind.session.CbtMessage;
import com.socraticmind.session.CbtSession;
import com.socraticmind.session.CbtSessionStore;
import com.socraticmind.session.CbtStage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/cbt/sessions")
public class CbtController {

    private final CbtFlow flow;
    private final CbtSessionStore store;

    /** 幂等表：同一 clientMessageId 重试不推进流程。第一版放内存，重启即失效。 */
    private final Map<String, TurnResult> processed = new ConcurrentHashMap<>();

    public CbtController(CbtFlow flow, CbtSessionStore store) {
        this.flow = flow;
        this.store = store;
    }

    public record StartRequest(String userId) {
    }

    public record MessageRequest(@NotBlank String message, String clientMessageId) {
    }

    public record ConfirmRequest(String thought) {
    }

    public record SessionView(
            String sessionId,
            String threadId,
            CbtStage stage,
            String stageLabel,
            int visibleIndex,
            String status,
            String targetProblem,
            String automaticThought,
            String candidateThought,
            String userConfirmedThought,
            String interventionType,
            String actionPlan,
            String actionResult,
            String summary,
            boolean awaitingUser,
            String safetyStatus,
            Instant createdAt
    ) {
        static SessionView of(CbtSession s) {
            return new SessionView(s.id(), s.threadId(), s.stage(), s.stage().label(),
                    s.stage().visibleIndex(), finished(s.stage()) ? "ended" : "running",
                    s.targetProblem(), s.automaticThought(), s.candidateThought(),
                    s.userConfirmedThought(), s.interventionType(), s.actionPlan(),
                    s.actionResult(), s.summary(), s.awaitingUser(), s.safetyStatus(), s.createdAt());
        }

        /**
         * 终止判定：安全暂停与显式结束之外，"走到总结"也必须算结束——
         * CbtFlow 在 ACTION_REVIEW 之后把 stage 落在 SESSION_SUMMARY 就不再推进，
         * 若只看 stage.isTerminal()，这一轮会一直被报成 running。
         */
        private static boolean finished(CbtStage stage) {
            return stage.isTerminal() || stage == CbtStage.SESSION_SUMMARY;
        }
    }

    @PostMapping
    public TurnResult start(@RequestBody(required = false) StartRequest request) {
        return flow.start(request == null ? null : request.userId());
    }

    @PostMapping("/{id}/messages")
    public TurnResult message(@PathVariable String id, @Valid @RequestBody MessageRequest request) {
        String key = id + "|" + (request.clientMessageId() == null ? "" : request.clientMessageId());
        if (request.clientMessageId() != null) {
            TurnResult previous = processed.get(key);
            if (previous != null) return previous;
        }
        TurnResult result = flow.handle(id, request.message());
        if (request.clientMessageId() != null) processed.put(key, result);
        return result;
    }

    /** 唯一的确认入口。前端不能通过 messages 推进这一节点。 */
    @PostMapping("/{id}/confirm")
    public TurnResult confirm(@PathVariable String id, @RequestBody(required = false) ConfirmRequest request) {
        return flow.confirm(id, request == null ? null : request.thought());
    }

    @GetMapping("/{id}")
    public ResponseEntity<SessionView> get(@PathVariable String id) {
        return store.find(id).map(s -> ResponseEntity.ok(SessionView.of(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/history")
    public List<CbtMessage> history(@PathVariable String id) {
        return flow.history(id);
    }

    @PostMapping("/{id}/end")
    public TurnResult end(@PathVariable String id) {
        return flow.end(id);
    }

    @GetMapping
    public List<SessionView> list(@RequestParam(required = false) String userId) {
        return store.findByUser(userId).stream().map(SessionView::of).toList();
    }
}
