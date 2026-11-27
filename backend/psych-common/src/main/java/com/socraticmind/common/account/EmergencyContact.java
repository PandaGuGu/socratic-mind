package com.socraticmind.common.account;

/**
 * 紧急联系人：用户自愿填写，只在安全暂停时的危机卡片里回显。
 *
 * 它不属于心理字段，也不参与任何判断：系统不会据此评级、不会外呼、不会替用户发送。
 * 会话里也可以单独填一份；账号上的这一份只作为"登录后新开一轮"的默认值。
 *
 * 手机号只做字符清洗（保留数字、+、-、空格），不做格式校验——
 * 校验失败不该让人在最需要留联系方式的时候填不进去。
 */
public record EmergencyContact(String name, String phone, String relation) {

    public static final EmergencyContact EMPTY = new EmergencyContact(null, null, null);

    public boolean absent() {
        return name == null && phone == null && relation == null;
    }

    public EmergencyContact sanitized() {
        return new EmergencyContact(text(name, 32), cleanPhone(phone), text(relation, 16));
    }

    private static String text(String value, int max) {
        if (value == null) return null;
        String t = value.strip();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String cleanPhone(String value) {
        if (value == null) return null;
        String t = value.replaceAll("[^0-9+\\- ]", "").strip();
        if (t.isEmpty()) return null;
        return t.length() > 32 ? t.substring(0, 32) : t;
    }
}
