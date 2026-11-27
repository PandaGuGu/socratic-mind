package com.socraticmind.app.auth;

import com.socraticmind.app.security.PasswordHasher;
import com.socraticmind.app.security.UserTokenService;
import com.socraticmind.app.web.support.UnauthorizedException;
import com.socraticmind.common.account.Account;
import com.socraticmind.common.account.EmergencyContact;
import com.socraticmind.common.port.AccountStore;
import com.socraticmind.common.support.Ids;
import com.socraticmind.session.CbtSessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 注册、登录、找回、改口令、注销。这里不碰流程、不碰心理字段，只做三件事：
 * 证明口令、签发令牌、维护账号的安全状态。
 *
 * 产品上的一条底线写在这里：**不登录也能完整使用**。
 * 账号只增加"换设备还能看到"与"多端同一份记录"，不是使用前提。
 * 因此注册时不会要求填手机号、真名或任何心理信息；邮箱只是登录名。
 *
 * 认领规则（v0.7.0 改）：登录/注册**不再自动认领**这台设备上的匿名会话，
 * 只返回 {@code pendingClaims} 数量；用户点"归到这个账号"才调 {@code /claim}。
 * 共享设备上"后来者悄悄接手前一个人的记录"这个窗口，靠这一道确认关掉。
 *
 * 令牌代数：改口令、重置口令、退出其他设备都会把 {@code token_epoch} +1，
 * 旧令牌里的 epoch 与账号当前值不符，产品接口立即 401。不需要维护令牌表。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_EMAIL = 254;
    private static final int MAX_NAME = 32;

    /**
     * 一次进入（注册/登录/找回）的结果。
     * {@code recoveryCode} 只在注册与"重新生成恢复码"时非空——它只显示这一次。
     */
    public record Result(String token, Account account, int pendingClaims, String recoveryCode) {
    }

    private final AccountStore accounts;
    private final CbtSessionStore sessions;
    private final UserTokenService tokens;
    private final PasswordHasher hasher;

    /**
     * 账号不存在时也跑一次同成本的摘要校验：
     * 否则"邮箱不存在"会比"口令错"快一个数量级，等于免费提供账号枚举。
     */
    private final String dummyHash;

    public AuthService(AccountStore accounts, CbtSessionStore sessions,
                       UserTokenService tokens, PasswordHasher hasher) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.tokens = tokens;
        this.hasher = hasher;
        this.dummyHash = hasher.hash("dummy-password-for-timing");
    }

    public Result register(String email, String rawPassword, String displayName,
                           UserTokenService.Identity current) {
        String normalized = normalizeEmail(email);
        if (rawPassword == null || !PasswordHasher.acceptable(rawPassword)) {
            throw new IllegalArgumentException("口令至少 8 位，且不超过 256 字节");
        }
        if (accounts.findByEmail(normalized).isPresent()) {
            throw new IllegalArgumentException("这个邮箱已经注册过了，可以直接登录");
        }
        Instant now = Instant.now();
        String recoveryCode = RecoveryCodes.generate();
        Account account = new Account(Ids.of("acc"), normalized, hasher.hash(rawPassword),
                cleanName(displayName, normalized), now, now, 0,
                hasher.hash(RecoveryCodes.normalize(recoveryCode)), null);
        accounts.save(account);
        log.info("新账号注册：{}", mask(normalized));
        return enter(account, current, recoveryCode);
    }

    public Result login(String email, String rawPassword, UserTokenService.Identity current) {
        String normalized = normalizeEmail(email);
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("请输入口令");
        }
        Optional<Account> found = accounts.findByEmail(normalized);
        if (found.isEmpty()) {
            hasher.matches(rawPassword, dummyHash);
            throw new IllegalArgumentException("邮箱或口令不对");
        }
        Account account = found.get();
        if (!hasher.matches(rawPassword, account.passwordHash())) {
            throw new IllegalArgumentException("邮箱或口令不对");
        }
        Instant now = Instant.now();
        accounts.touchLogin(account.id(), now);
        return enter(account.withLastLogin(now), current, null);
    }

    /** /api/auth/me 用：只回账号视图，不回令牌。令牌代数不符时按未登录处理。 */
    public Optional<Account> current(UserTokenService.Identity identity) {
        if (identity == null || !identity.hasAccount()) return Optional.empty();
        return accounts.findById(identity.accountId())
                .filter(a -> a.tokenEpoch() == identity.epoch());
    }

    /**
     * 认领这台设备上尚未归属账号的匿名会话。只在用户明确点过之后调用。
     * 不认领已属其他账号的：换账号登录时，前者被认领过的记录不会被后者接手。
     */
    public int claim(UserTokenService.Identity who) {
        Account account = requireAccount(who);
        int claimed = sessions.claim(who.userId(), account.id());
        if (claimed > 0) log.info("认领会话 {} 轮，账号 {}", claimed, mask(account.email()));
        return claimed;
    }

    /**
     * 改口令：验证当前口令，把令牌代数 +1，给当前设备换一枚新令牌。
     * 其他设备上的旧令牌随即失效——这就是"改完口令，别处登录的都要重来"。
     */
    @Transactional
    public Result changePassword(UserTokenService.Identity who, String currentPassword, String newPassword) {
        Account account = requireAccount(who);
        if (!hasher.matches(currentPassword, account.passwordHash())) {
            throw new IllegalArgumentException("当前口令不对");
        }
        if (!PasswordHasher.acceptable(newPassword)) {
            throw new IllegalArgumentException("新口令至少 8 位，且不超过 256 字节");
        }
        int epoch = account.tokenEpoch() + 1;
        Account updated = account.withPassword(hasher.hash(newPassword), epoch);
        accounts.updateSecurity(updated);
        log.info("口令已修改，令牌代数推进到 {}：{}", epoch, mask(account.email()));
        return enter(updated, who, null);
    }

    /**
     * 退出其他设备：只推进令牌代数，再给当前设备签一枚新令牌。
     * 当前设备因此不受影响，其他设备下一次请求就会 401。
     */
    @Transactional
    public Result logoutOtherDevices(UserTokenService.Identity who) {
        Account account = requireAccount(who);
        int epoch = account.tokenEpoch() + 1;
        Account updated = account.withTokenEpoch(epoch);
        accounts.updateSecurity(updated);
        log.info("已退出其他设备，令牌代数推进到 {}：{}", epoch, mask(account.email()));
        return enter(updated, who, null);
    }

    /**
     * 用恢复码重置口令。没有发信通道也能自救：恢复码在注册时只显示一次，用一次即作废。
     *
     * 错误一律回"邮箱或恢复码不对"，不区分账号是否存在、恢复码是否用过的——
     * 与登录同样，避免把"这个人有没有账号"变成可探测信息。
     */
    @Transactional
    public Result recover(String email, String recoveryCode, String newPassword,
                          UserTokenService.Identity current) {
        String normalized = normalizeEmail(email);
        Optional<Account> found = accounts.findByEmail(normalized);
        String code = RecoveryCodes.normalize(recoveryCode);
        if (found.isEmpty() || code.isEmpty()) {
            hasher.matches(code.isEmpty() ? "empty" : code, dummyHash);
            throw new IllegalArgumentException("邮箱或恢复码不对");
        }
        Account account = found.get();
        if (account.recoveryHash() == null || account.recoveryUsedAt() != null
                || !hasher.matches(code, account.recoveryHash())) {
            throw new IllegalArgumentException("邮箱或恢复码不对");
        }
        if (!PasswordHasher.acceptable(newPassword)) {
            throw new IllegalArgumentException("新口令至少 8 位，且不超过 256 字节");
        }
        int epoch = account.tokenEpoch() + 1;
        Account updated = account.withRecoveredPassword(hasher.hash(newPassword), epoch, Instant.now());
        accounts.updateSecurity(updated);
        log.info("账号已用恢复码重置口令，旧令牌全部失效：{}", mask(account.email()));
        return enter(updated, current, null);
    }

    /** 重新生成恢复码：旧码立即作废，新码只在这里返回一次。 */
    @Transactional
    public String rotateRecoveryCode(UserTokenService.Identity who) {
        Account account = requireAccount(who);
        String code = RecoveryCodes.generate();
        accounts.updateSecurity(account.withRecovery(hasher.hash(RecoveryCodes.normalize(code))));
        log.info("恢复码已重新生成：{}", mask(account.email()));
        return code;
    }

    /**
     * 注销账号：先解除认领，再删账号本身，返回解除的会话数。
     *
     * 注销即吊销：账号没了，产品接口查不到账号，同一枚令牌立刻 401。
     * 会话不跟着删：它的第一归属是设备，账号只是第二把钥匙。
     */
    @Transactional
    public int deleteAccount(UserTokenService.Identity who, String rawPassword) {
        Account account = requireAccount(who);
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("请输入口令以确认注销");
        }
        if (!hasher.matches(rawPassword, account.passwordHash())) {
            throw new IllegalArgumentException("口令不对");
        }
        int released = sessions.releaseAccount(account.id());
        accounts.delete(account.id());
        log.info("账号已注销：{}，解除认领 {} 轮会话", mask(account.email()), released);
        return released;
    }

    private Result enter(Account account, UserTokenService.Identity current, String recoveryCode) {
        // 账号令牌里带着设备 id：登录后新开的会话仍记在这台设备名下，退出登录才可逆。
        String deviceUserId = current == null || current.userId() == null || current.userId().isBlank()
                ? tokens.newUserId()
                : current.userId();
        int pending = sessions.countClaimable(deviceUserId);
        String token = tokens.issueAccount(deviceUserId, account.id(), account.tokenEpoch());
        return new Result(token, account, pending, recoveryCode);
    }

    /** 读取账号上保存的紧急联系人；没填过返回空对象。 */
    public EmergencyContact contact(UserTokenService.Identity who) {
        Account account = requireAccount(who);
        return accounts.findContact(account.id()).orElse(EmergencyContact.EMPTY);
    }

    /**
     * 保存账号级紧急联系人。传空对象即清空。
     * 只做字符清洗，不做格式校验：留联系方式这件事不该被校验规则挡住。
     */
    public EmergencyContact saveContact(UserTokenService.Identity who, EmergencyContact contact) {
        Account account = requireAccount(who);
        EmergencyContact clean = contact == null ? EmergencyContact.EMPTY : contact.sanitized();
        accounts.saveContact(account.id(), clean);
        return clean;
    }

    /**
     * 取账号并要求令牌代数一致。所有"已登录才能做"的动作都走这里，
     * 避免某个接口忘了比较 epoch 而让旧令牌继续可用。
     */
    private Account requireAccount(UserTokenService.Identity who) {
        if (who == null || !who.hasAccount()) {
            throw new UnauthorizedException("请先登录");
        }
        Account account = accounts.findById(who.accountId())
                .orElseThrow(() -> new UnauthorizedException("账号已注销，令牌随之失效"));
        if (account.tokenEpoch() != who.epoch()) {
            throw new UnauthorizedException("登录状态已失效，请重新登录");
        }
        return account;
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) throw new IllegalArgumentException("请填写邮箱");
        String trimmed = email.strip();
        if (trimmed.length() > MAX_EMAIL || !EMAIL.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("邮箱格式不对");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String cleanName(String displayName, String email) {
        if (displayName == null || displayName.isBlank()) {
            int at = email.indexOf('@');
            return at > 0 ? email.substring(0, Math.min(at, MAX_NAME)) : "我";
        }
        String stripped = displayName.strip();
        return stripped.length() > MAX_NAME ? stripped.substring(0, MAX_NAME) : stripped;
    }

    /** 日志里不留完整邮箱：本地部分只留前两位。 */
    private static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 2) return "***" + email.substring(Math.max(at, 0));
        return email.substring(0, 2) + "***" + email.substring(at);
    }
}
