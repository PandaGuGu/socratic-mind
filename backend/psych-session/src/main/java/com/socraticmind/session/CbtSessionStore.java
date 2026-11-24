package com.socraticmind.session;

import java.util.List;
import java.util.Optional;

/** 会话存储端口。内存实现用于开发；PostgreSQL 实现按 kb_* 同样的方式追加。 */
public interface CbtSessionStore {

    void save(CbtSession session);

    Optional<CbtSession> find(String sessionId);

    /**
     * 按设备归属查询。传 null 表示"全库"，只给内部控制台用——
     * 产品接口不得传 null，否则又变成可枚举的全量读。
     */
    List<CbtSession> findByUser(String userId);

    /**
     * 一次请求能看到的会话。两个参数是**二选一**，不是并集：
     * <ul>
     *   <li>带 accountId：只看该账号名下的会话（账号令牌不继承设备归属，
     *       否则同一浏览器换账号就能读到前一个账号的记录）；</li>
     *   <li>accountId 为空：只看该设备的会话（未登录）。</li>
     * </ul>
     *
     * 写成一个方法而不是让调用方查两次再合并：排序、去重、null 语义只解释一次，
     * 换实现时不会出现"内存实现对了、JDBC 没对"。
     */
    List<CbtSession> findVisible(String userId, String accountId);

    /**
     * 同 {@link #findVisible}，但只取一页，按创建时间倒序。
     *
     * 游标是 (createdAt, id) 而不是单一时间戳：同一毫秒建了两轮时，
     * 只按时间翻页会把其中一轮漏掉——这种漏数据最难被发现，因为页面看起来一切正常。
     * beforeAt 与 beforeId 都为 null 表示第一页。
     */
    List<CbtSession> findVisiblePage(String userId, String accountId,
                                     java.time.Instant beforeAt, String beforeId, int limit);

    /**
     * 把某台设备上尚未认领的会话挂到账号下，返回认领条数。
     *
     * 只认领 account_id 为空的：同一台设备换人登录时，上一个人的记录不会被第二个人接手。
     */
    int claim(String userId, String accountId);

    /** 只数不认领：登录后先问用户"要不要把这些匿名记录归到账号下"（v0.7.0）。 */
    int countClaimable(String userId);

    /**
     * 解除某个账号对全部会话的认领，返回条数。账号注销时调用。
     *
     * 会话本身不删：它的第一归属是设备，账号只是"多看一批"的第二把钥匙。
     * 注销账号等于把钥匙还回去——这台设备重新用自己的令牌就能读到。
     */
    int releaseAccount(String accountId);
}
