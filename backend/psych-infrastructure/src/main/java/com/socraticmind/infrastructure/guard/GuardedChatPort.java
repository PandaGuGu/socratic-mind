package com.socraticmind.infrastructure.guard;

import com.socraticmind.common.guard.GuardRules;
import com.socraticmind.common.guard.GuardViolation;
import com.socraticmind.common.guard.ModelOutputRejectedException;
import com.socraticmind.common.guard.ProfileRule;
import com.socraticmind.common.port.ChatPort;

import java.util.List;

/**
 * 输出守卫：包在任何 ChatPort 外面，让"模型说了什么"先过一遍确定性规则。
 *
 * 为什么做成装饰器而不是写在业务里：规则要能被运维改，就不能散在十个 if 里。
 * 装饰器保证**所有**模型调用必经同一道闸，新增调用点时不会漏。
 *
 * 职责边界：只判断"能不能出"，不判断"改成什么"。违规重试仍由本类发起（因为只有它知道违规原因），
 * 但最终兜底话术留给调用方——基础设施层不该写业务文案。
 */
public class GuardedChatPort implements ChatPort {

    private final ChatPort delegate;
    private final OutputPolicyStore store;
    private final GuardAuditLog audit;

    public GuardedChatPort(ChatPort delegate, OutputPolicyStore store, GuardAuditLog audit) {
        this.delegate = delegate;
        this.store = store;
        this.audit = audit;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, null);
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, String profile) {
        ProfileRule rule = store.get().ruleOf(profile);
        String output = delegate.chat(systemPrompt, userPrompt);

        if (rule == null || !rule.enabled()) {
            return output;
        }

        List<GuardViolation> violations = GuardRules.inspect(output, userPrompt, rule);
        if (violations.isEmpty()) {
            audit.ok(profile);
            return output;
        }

        int attempt = 0;
        while (attempt < rule.repairAttempts()) {
            attempt++;
            String repaired = delegate.chat(systemPrompt, userPrompt + repairInstruction(violations));
            List<GuardViolation> after = GuardRules.inspect(repaired, userPrompt, rule);
            if (after.isEmpty()) {
                audit.repaired(profile, violations, repaired);
                return repaired;
            }
            violations = after;
        }

        audit.rejected(profile, violations, output);
        throw new ModelOutputRejectedException(profile, violations);
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    private static String repairInstruction(List<GuardViolation> violations) {
        StringBuilder sb = new StringBuilder("\n\n上一次输出没有通过校验：");
        for (GuardViolation v : violations) {
            sb.append("\n- ").append(v.detail());
        }
        sb.append("\n请重写，只输出正文本身，不要解释，也不要提及这些规则。");
        return sb.toString();
    }
}
