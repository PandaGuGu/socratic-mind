package com.socraticmind.common.port;

import com.socraticmind.common.account.Account;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 账号存储端口。与 {@code CbtSessionStore} 同样的理由：换库只换实现。
 *
 * 约定：email 由调用方（AuthService）统一转小写后再传入，存储层不再做归一化，
 * 避免"内存实现转了、JDBC 实现忘了"这类只在某一种配置下出现的重复注册。
 */
public interface AccountStore {

    Optional<Account> findById(String id);

    Optional<Account> findByEmail(String email);

    /** 邮箱冲突由存储层的唯一约束兜底，应用层先查一次只是为了给出人话错误。 */
    void save(Account account);

    void touchLogin(String id, Instant at);

    /**
     * 只更新安全相关字段：口令摘要、令牌代数、恢复码摘要与使用时间。
     * 单独一个方法而不是整行 update，是为了避免"顺手把邮箱写回去"这类越权改写。
     */
    void updateSecurity(Account account);

    /** 运营视图用：只读列表，按创建时间倒序。不做分页——账号数不是这个量级的瓶颈。 */
    List<Account> findAll();

    /**
     * 注销：删除账号本身。会话不跟着删——它们的第一归属是设备（ADR-0009），
     * 调用方需要先解除认领（{@code CbtSessionStore.releaseAccount}），再删账号。
     */
    void delete(String id);
}
