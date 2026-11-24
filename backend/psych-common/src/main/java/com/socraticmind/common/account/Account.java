package com.socraticmind.common.account;

import java.time.Instant;

/**
 * 一个账号：只存"能证明你是你"的最小集合，不存任何心理字段。
 *
 * 为什么心理记录不挂在账号上：会话仍以 {@code owner_id}（设备匿名身份）为第一归属，
 * 账号只是"可以多看一批会话"的第二把钥匙（见 ADR-0009）。
 * 这样"不注册也完整可用""退出登录后本机记录仍在"两件事才同时成立。
 *
 * 口令与恢复码都是 PBKDF2 摘要，不是明文，也不是可逆加密；这里只保存结果，校验在应用层。
 *
 * {@code tokenEpoch} 是"这一账号当前签发的令牌代数"：改口令、退出其他设备、重置口令都会 +1，
 * 旧令牌里的 epoch 与之不符，立刻失效。它比维护一张令牌表便宜，也比"只能等密钥轮换"诚实。
 */
public record Account(
        String id,
        String email,
        String passwordHash,
        String displayName,
        Instant createdAt,
        Instant lastLoginAt,
        int tokenEpoch,
        String recoveryHash,
        Instant recoveryUsedAt
) {

    public Account withLastLogin(Instant at) {
        return new Account(id, email, passwordHash, displayName, createdAt, at,
                tokenEpoch, recoveryHash, recoveryUsedAt);
    }

    /** 改口令 / 重置口令：同时把令牌代数推进一格，旧令牌随即失效。 */
    public Account withPassword(String newHash, int newEpoch) {
        return new Account(id, email, newHash, displayName, createdAt, lastLoginAt,
                newEpoch, recoveryHash, recoveryUsedAt);
    }

    /** 只推进令牌代数，不动口令（退出其他设备）。 */
    public Account withTokenEpoch(int newEpoch) {
        return new Account(id, email, passwordHash, displayName, createdAt, lastLoginAt,
                newEpoch, recoveryHash, recoveryUsedAt);
    }

    /** 换一枚新的恢复码；旧码同时作废。 */
    public Account withRecovery(String newHash) {
        return new Account(id, email, passwordHash, displayName, createdAt, lastLoginAt,
                tokenEpoch, newHash, null);
    }

    /** 用恢复码重置口令：恢复码一次性，用后立刻作废（保留摘要与使用时间供审计）。 */
    public Account withRecoveredPassword(String newHash, int newEpoch, Instant usedAt) {
        return new Account(id, email, newHash, displayName, createdAt, lastLoginAt,
                newEpoch, recoveryHash, usedAt);
    }
}
