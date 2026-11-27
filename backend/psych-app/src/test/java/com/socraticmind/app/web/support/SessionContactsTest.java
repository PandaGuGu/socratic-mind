package com.socraticmind.app.web.support;

import com.socraticmind.common.account.Account;
import com.socraticmind.common.account.EmergencyContact;
import com.socraticmind.infrastructure.store.InMemoryAccountStore;
import com.socraticmind.infrastructure.store.InMemoryCbtSessionStore;
import com.socraticmind.session.CbtSession;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 账号联系人如何进入会话：只填空的，不覆盖已有的。
 */
class SessionContactsTest {

    private final InMemoryCbtSessionStore store = new InMemoryCbtSessionStore();
    private final InMemoryAccountStore accounts = new InMemoryAccountStore();

    @Test
    void fillsAnEmptySessionWithTheAccountContact() {
        accounts.save(account("acc_1"));
        accounts.saveContact("acc_1", new EmergencyContact("小周", "13800001111", "室友"));
        store.save(new CbtSession("cbt_1", "thread_1", "u1"));

        SessionContacts.prefill(store, accounts, "cbt_1", "acc_1");

        CbtSession saved = store.find("cbt_1").orElseThrow();
        assertEquals("小周", saved.contactName());
        assertEquals("13800001111", saved.contactPhone());
        assertEquals("室友", saved.contactRelation());
    }

    @Test
    void neverOverridesWhatTheUserAlreadyPutInTheSession() {
        accounts.save(account("acc_2"));
        accounts.saveContact("acc_2", new EmergencyContact("小周", "13800001111", "室友"));
        CbtSession session = new CbtSession("cbt_2", "thread_2", "u1");
        session.setContact("老李", "13900002222", "同事");
        store.save(session);

        SessionContacts.prefill(store, accounts, "cbt_2", "acc_2");

        CbtSession saved = store.find("cbt_2").orElseThrow();
        assertEquals("老李", saved.contactName(), "会话里填过的人优先");
        assertEquals("13900002222", saved.contactPhone());
    }

    @Test
    void emptyAccountContactLeavesTheSessionAlone() {
        accounts.save(account("acc_3"));
        store.save(new CbtSession("cbt_3", "thread_3", "u1"));

        SessionContacts.prefill(store, accounts, "cbt_3", "acc_3");

        assertNull(store.find("cbt_3").orElseThrow().contactName());
    }

    private static Account account(String id) {
        return new Account(id, id + "@example.com", "pbkdf2-hash", null,
                Instant.now(), null, 0, null, null);
    }
}
