package com.socraticmind.app.seed;

import com.socraticmind.app.security.DevOperatorToken;
import com.socraticmind.common.model.Operator;
import com.socraticmind.common.port.OrgStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * 组织模式的启动准备。
 *
 * 两件事，都必须**幂等**：
 * 1. 库为空时建一个租户与团队；
 * 2. 开发档（dev-hint）确保存在一个固定令牌的管理员。
 *
 * 第 2 条是踩过的坑：最初只在"库为空"时发放令牌，于是本机第二次启动拿不到钥匙，
 * 后台所有写操作 401，看起来像功能坏了。现在令牌是确定性的
 * （{@code dev-operator-token}，与 {@code dev-internal-token} 同一姿态），
 * 重启后按令牌查得到就直接复用，查不到才新建。
 *
 * 正式环境不播种固定令牌：那里应该由管理员建组织、一次性发放随机令牌。
 */
@Configuration
@ConditionalOnProperty(name = "psych.org.enabled", havingValue = "true")
public class SeedOrgLoader {

    private static final Logger log = LoggerFactory.getLogger(SeedOrgLoader.class);

    /** 开发档专用固定令牌。只在 dev-hint 打开时使用，生产不得依赖它。 */
    static final String DEV_OPERATOR_TOKEN = "dev-operator-token";
    static final String DEV_LOGIN_USERNAME = "admin";
    static final String DEV_LOGIN_PASSWORD = "dev-admin-pass";

    @Bean
    @Order(50)
    ApplicationRunner seedOrgRunner(OrgStore orgStore, DevOperatorToken devToken,
                                    com.socraticmind.app.security.PasswordHasher hasher,
                                    @Value("${psych.internal.dev-hint:false}") boolean devHint) {
        return args -> {
            var tenant = orgStore.tenants().stream().findFirst().orElse(null);
            if (tenant == null) {
                tenant = orgStore.createTenant("本机开发租户");
                log.info("组织域为空，已建租户：{}", tenant.name());
            }
            var finalTenant = tenant;
            var team = orgStore.teams(finalTenant.id()).stream().findFirst().orElse(null);
            if (team == null) {
                team = orgStore.createTeam(finalTenant.id(), "cbt");
                log.info("已建团队：{}", team.name());
            }

            if (devHint) {
                if (orgStore.findByToken(DEV_OPERATOR_TOKEN).isEmpty()) {
                    orgStore.createMemberWithToken(finalTenant.id(), team.id(),
                            "本机管理员", Operator.ADMIN, DEV_OPERATOR_TOKEN);
                    log.info("开发档：已建操作者「本机管理员」，令牌 {} 会自动填入 /internal 页面",
                            DEV_OPERATOR_TOKEN);
                } else {
                    log.info("开发档：操作者令牌已存在，直接复用");
                }
                devToken.set(DEV_OPERATOR_TOKEN);

                // 登录名与口令也播种：否则"登录页有了但没有账号"，第一次打开仍然进不去。
                // 这两个值是公开的，和 dev-internal-token 同一姿态，只在 dev-hint 下存在。
                if (orgStore.credential(DEV_LOGIN_USERNAME).isEmpty()) {
                    var op = orgStore.findByToken(DEV_OPERATOR_TOKEN).orElseThrow();
                    orgStore.setCredential(op.id(), DEV_LOGIN_USERNAME, hasher.hash(DEV_LOGIN_PASSWORD));
                    log.info("开发档：登录名 {} / 口令 {}（仅本机，正式环境请改）",
                            DEV_LOGIN_USERNAME, DEV_LOGIN_PASSWORD);
                } else {
                    log.info("开发档：登录名 {} 已存在", DEV_LOGIN_USERNAME);
                }
                return;
            }

            if (orgStore.members(finalTenant.id()).isEmpty()) {
                var issued = orgStore.createMember(finalTenant.id(), team.id(), "管理员", Operator.ADMIN);
                log.warn("组织模式已开启但未开 dev-hint：请用下面这次性令牌配置管理员，它不会再次显示：{}",
                        issued.token());
            }
        };
    }
}
