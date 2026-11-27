package com.socraticmind.app.web.support;

import com.socraticmind.common.account.EmergencyContact;
import com.socraticmind.common.port.AccountStore;
import com.socraticmind.session.CbtSessionStore;

/**
 * 新开一轮时，把账号上保存的紧急联系人带进会话。
 *
 * 两条规则写在代码里，避免每个入口各写一遍：
 * 1. 只填空会话——会话里已经填过（或用户改过）的值优先，账号值不能覆盖它；
 * 2. 账号上没有联系人时什么也不做，不写空值。
 *
 * 单独一个类是为了能直接单测：走一遍 HTTP 开会话会撞上"每小时新会话数"的限流，
 * 那条限流在真实环境里是对的，不该为了测试放宽。
 */
public final class SessionContacts {

    private SessionContacts() {
    }

    public static void prefill(CbtSessionStore store, AccountStore accounts,
                               String sessionId, String accountId) {
        if (sessionId == null || accountId == null) return;
        EmergencyContact contact = accounts.findContact(accountId).orElse(EmergencyContact.EMPTY);
        if (contact.absent()) return;
        store.find(sessionId).ifPresent(session -> {
            if (session.contactName() != null || session.contactPhone() != null
                    || session.contactRelation() != null) {
                return;
            }
            session.setContact(contact.name(), contact.phone(), contact.relation());
            store.save(session);
        });
    }
}
