package com.socraticmind.infrastructure.store;

import com.socraticmind.common.account.Account;
import com.socraticmind.common.account.EmergencyContact;
import com.socraticmind.common.port.AccountStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 账号的 JDBC 实现。DDL 见 {@code db/schema-cbt.sql} 的 app_account。
 *
 * 邮箱唯一性靠数据库唯一索引，不靠"先查再插"——两个人的注册请求同时到达时，
 * 先查再插两边都会看到"不存在"。这里把唯一键冲突翻译成人话错误，交给接口层。
 */
@Component
@ConditionalOnProperty(name = "psych.db.enabled", havingValue = "true")
public class JdbcAccountStore implements AccountStore {

    private final JdbcTemplate jdbc;

    public JdbcAccountStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Account> findById(String id) {
        List<Account> rows = jdbc.query("SELECT * FROM app_account WHERE id = ?", (rs, i) -> read(rs), id);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<Account> findByEmail(String email) {
        List<Account> rows = jdbc.query("SELECT * FROM app_account WHERE email = ?", (rs, i) -> read(rs), email);
        return rows.stream().findFirst();
    }

    @Override
    public void save(Account account) {
        try {
            jdbc.update("""
                    INSERT INTO app_account (id, email, password_hash, display_name, created_at, last_login_at,
                      token_epoch, recovery_hash, recovery_used_at)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, account.id(), account.email(), account.passwordHash(), account.displayName(),
                    Timestamp.from(account.createdAt()),
                    account.lastLoginAt() == null ? null : Timestamp.from(account.lastLoginAt()),
                    account.tokenEpoch(), account.recoveryHash(),
                    account.recoveryUsedAt() == null ? null : Timestamp.from(account.recoveryUsedAt()));
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("这个邮箱已经注册过了");
        }
    }

    @Override
    public void touchLogin(String id, Instant at) {
        jdbc.update("UPDATE app_account SET last_login_at = ? WHERE id = ?", Timestamp.from(at), id);
    }

    @Override
    public void updateSecurity(Account account) {
        jdbc.update("""
                UPDATE app_account SET password_hash = ?, token_epoch = ?, recovery_hash = ?, recovery_used_at = ?
                WHERE id = ?
                """, account.passwordHash(), account.tokenEpoch(), account.recoveryHash(),
                account.recoveryUsedAt() == null ? null : Timestamp.from(account.recoveryUsedAt()),
                account.id());
    }

    @Override
    public Optional<EmergencyContact> findContact(String accountId) {
        List<EmergencyContact> rows = jdbc.query(
                "SELECT contact_name, contact_phone, contact_relation FROM app_account WHERE id = ?",
                (rs, i) -> new EmergencyContact(rs.getString(1), rs.getString(2), rs.getString(3)),
                accountId);
        return rows.stream().findFirst().filter(c -> !c.absent());
    }

    @Override
    public void saveContact(String accountId, EmergencyContact contact) {
        EmergencyContact c = contact == null ? EmergencyContact.EMPTY : contact.sanitized();
        jdbc.update(
                "UPDATE app_account SET contact_name = ?, contact_phone = ?, contact_relation = ? WHERE id = ?",
                c.name(), c.phone(), c.relation(), accountId);
    }

    @Override
    public List<Account> findAll() {
        return jdbc.query("SELECT * FROM app_account ORDER BY created_at DESC", (rs, i) -> read(rs));
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM app_account WHERE id = ?", id);
    }

    private static Account read(ResultSet rs) throws java.sql.SQLException {
        Timestamp login = rs.getTimestamp("last_login_at");
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp recoveryUsed = rs.getTimestamp("recovery_used_at");
        return new Account(rs.getString("id"), rs.getString("email"), rs.getString("password_hash"),
                rs.getString("display_name"),
                created == null ? null : created.toInstant(),
                login == null ? null : login.toInstant(),
                rs.getInt("token_epoch"),
                rs.getString("recovery_hash"),
                recoveryUsed == null ? null : recoveryUsed.toInstant());
    }
}
