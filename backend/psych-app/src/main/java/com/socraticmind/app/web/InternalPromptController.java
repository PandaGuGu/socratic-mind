package com.socraticmind.app.web;

import com.socraticmind.app.security.RequirePerm;
import com.socraticmind.common.model.Operator;
import com.socraticmind.common.prompt.PromptConfig;
import com.socraticmind.infrastructure.prompt.PromptStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提示词与知识问答参数的后台接口。
 *
 * 门外提示词、知识问答生成提示词、每次检索条数都由运营在「规则与参数」里维护；
 * 保存即生效（FreeChatFlow / RagService / KnowledgeFlow 都从 PromptStore 读取）。
 */
@RestController
@RequestMapping("/api/internal")
public class InternalPromptController {

    private final PromptStore store;

    public InternalPromptController(PromptStore store) {
        this.store = store;
    }

    public record PromptView(int version, String updatedAt, String updatedBy,
                             String freeChat, String knowledgeAnswer, int knowledgeTopK,
                             String path, boolean persisted) {
        static PromptView of(PromptConfig c, PromptStore store) {
            return new PromptView(c.version(), c.updatedAt(), c.updatedBy(),
                    c.freeChat(), c.knowledgeAnswer(), c.knowledgeTopK(),
                    store.path().toString(), store.exists());
        }
    }

    public record PromptRequest(String freeChat, String knowledgeAnswer, Integer knowledgeTopK) {
    }

    @GetMapping("/prompts")
    public PromptView view() {
        return PromptView.of(store.get(), store);
    }

    @PutMapping("/prompts")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public ResponseEntity<?> save(@RequestBody PromptRequest request,
                                  @RequestAttribute(name = "sm.operator", required = false) Operator operator) {
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "提示词内容不能为空"));
        }
        try {
            PromptConfig saved = store.save(new PromptConfig(
                    store.get().version(), store.get().updatedAt(), store.get().updatedBy(),
                    request.freeChat(), request.knowledgeAnswer(),
                    request.knowledgeTopK() == null ? store.get().knowledgeTopK() : request.knowledgeTopK()),
                    operator == null ? "ops" : operator.display());
            return ResponseEntity.ok(PromptView.of(saved, store));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
