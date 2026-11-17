package com.socraticmind.common.guard;

import java.util.List;

/**
 * 模型输出没过闸。调用方必须自己决定兜底话术——守卫只负责说"不行，原因是这些"，
 * 不替业务决定"那改成什么"，否则兜底文案会散落在基础设施层。
 */
public class ModelOutputRejectedException extends RuntimeException {

    private final String profile;
    private final List<GuardViolation> violations;

    public ModelOutputRejectedException(String profile, List<GuardViolation> violations) {
        super("输出未通过策略校验 [" + profile + "]：" + summary(violations));
        this.profile = profile;
        this.violations = List.copyOf(violations);
    }

    public String profile() {
        return profile;
    }

    public List<GuardViolation> violations() {
        return violations;
    }

    /** 第一条违规的规则名，写进轮次轨迹用。 */
    public String rule() {
        return violations.isEmpty() ? "unknown" : violations.get(0).rule();
    }

    private static String summary(List<GuardViolation> violations) {
        return violations.stream().map(GuardViolation::rule).distinct().reduce((a, b) -> a + "," + b).orElse("unknown");
    }
}
