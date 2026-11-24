package com.socraticmind.infrastructure.store;

import com.socraticmind.common.account.Account;
import com.socraticmind.common.port.AccountStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账号内存存储：默认档与测试用，重启即丢。
 *
 * 条件挂在"有没有数据库"上，而不是挂在"会话存哪里"上：账号只要有库就该落库，
 * 与会话是否持久化是两件独立的事（可以只持久化账号，也可以只持久化会话）。
 */
@Component
@ConditionalOnProperty(name = "psych.db.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryAccountStore implements AccountStore {

    private final Map<String, Account> byEmail = new ConcurrentHashMap<>();
    private final Map<String, Account> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<Account> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Account> findByEmail(String email) {
        return Optional.ofNullable(byEmail.get(normalize(email)));
    }

    @Override
    public void save(Account account) {
        String key = normalize(account.email());
        if (byEmail.putIfAbsent(key, account) != null) {
            throw new IllegalArgumentException("这个邮箱已经注册过了");
        }
        byId.put(account.id(), account);
    }

    @Override
    public void touchLogin(String id, Instant at) {
        byId.computeIfPresent(id, (key, account) -> {
            Account updated = account.withLastLogin(at);
            byEmail.put(normalize(updated.email()), updated);
            return updated;
        });
    }

    @Override
    public void updateSecurity(Account account) {
        byId.computeIfPresent(account.id(), (key, old) -> {
            Account updated = new Account(old.id(), old.email(), account.passwordHash(),
                    old.displayName(), old.createdAt(), old.lastLoginAt(),
                    account.tokenEpoch(), account.recoveryHash(), account.recoveryUsedAt());
            byEmail.put(normalize(updated.email()), updated);
            return updated;
        });
    }

    @Override
    public List<Account> findAll() {
        return byId.values().stream()
                .sorted(Comparator.comparing(Account::createdAt).reversed())
                .toList();
    }

    @Override
    public void delete(String id) {
        Account removed = byId.remove(id);
        if (removed != null) byEmail.remove(normalize(removed.email()));
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
