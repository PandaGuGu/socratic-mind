package com.socraticmind.session;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 安全暂停时给用户看的"危机卡片"。
 *
 * 为什么不是"自动外呼"：我们没有资质，也不能替用户判断该不该惊动别人；
 * 误报的代价由用户承担。所以卡片做三件事——
 * 1. 把热线放在最前面（12356 / 120），这是不依赖任何人的兜底；
 * 2. 把用户自己填的联系人原样回显，手机号不脱敏（这是他自己的数据）；
 * 3. 生成一段**用户自己发**的通知文案，复制即可发给对方。
 *
 * 触发的是"人"，不是"系统"：我们不替用户按发送键，但把该说的话先写好。
 */
public record CrisisCard(
        List<String> hotlines,
        String contactName,
        String contactPhone,
        String contactRelation,
        String notice,
        Instant raisedAt,
        boolean acknowledged
) {

    public static final List<String> DEFAULT_HOTLINES = List.of(
            "12356　全国统一心理援助热线（24 小时）",
            "120　　急救");

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("M 月 d 日 HH:mm").withZone(ZoneId.systemDefault());

    public static CrisisCard of(CbtSession session, String reason) {
        return new CrisisCard(DEFAULT_HOTLINES, session.contactName(), session.contactPhone(),
                session.contactRelation(), buildNotice(),
                Instant.now(), session.safetyAckAt() != null);
    }

    /**
     * 文案不写"你被判定为有风险"——那等于给用户一句他不想转发的话。
     * 也不写规则 id 与分数：卡片是给用户用的，不是给系统看的。
     */
    private static String buildNotice() {
        String when = WHEN.format(Instant.now());
        return "（这是我在一个心理自助练习里生成的一段话，发不发由我决定。）\n"
                + when + "，我在练习里说了一些让自己担心的话。"
                + "如果方便，能不能联系我一下？不用劝我，聊两句就行。";
    }
}
