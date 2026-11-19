package com.socraticmind.agent.config;

/**
 * 运行参数：限流与长度这类"要能在线调"的数值。
 *
 * 为什么单独一份：它们跟策略、流程、安全规则一样，都是运维会在半夜改的东西
 * （比如上游模型变慢要降频、有人刷接口要收紧），不该为了改一个数字重新打包。
 *
 * 注意：幂等表容量仍是代码常量——它决定的是内存上限，不是业务策略，
 * 做成可调只会给人"调了就有用"的错觉。
 */
public record RuntimeLimits(
        int version,
        String updatedAt,
        String updatedBy,
        int maxMessageChars,
        int messagesPerMinute,
        int newSessionsPerHour
) {

    public static RuntimeLimits defaults() {
        return new RuntimeLimits(1, null, "system", 2000, 60, 30);
    }
}
