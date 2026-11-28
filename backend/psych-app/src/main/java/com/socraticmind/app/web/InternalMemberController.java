package com.socraticmind.app.web;

import com.socraticmind.app.security.PasswordHasher;
import com.socraticmind.app.security.RequirePerm;
import com.socraticmind.common.model.Operator;
import com.socraticmind.common.port.OrgStore;
import com.socraticmind.app.web.support.ConflictException;
import com.socraticmind.app.web.support.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 后台成员：管理员在这里建"运营"和"医生"，并把职责分给具体的人。
 *
 * 之前只有管理员：播种脚本建一个 admin，之后没有任何入口加人，于是所有事都由同一个身份做——
 * 审核签字和管理成员用的是同一把钥匙，出了事也分不清是谁的职责。
 *
 * 这个控制器本身只对管理员开放（类上的 {@link RequirePerm}）；建出来的成员能被
 * 知识入库、审核发布这些接口按职责挡住，见 {@code Operator.Perm}。
 */
@RestController
@RequestMapping("/api/internal/members")
@RequirePerm(Operator.Perm.MANAGE_MEMBERS)
public class InternalMemberController {

    private static final Set<String> ROLES =
            Set.of(Operator.ADMIN, Operator.OPERATOR, Operator.REVIEWER, Operator.VIEWER);

    private final OrgStore orgStore;
    private final PasswordHasher hasher;
    /** 与后台改口令同一个开关：本机演示档允许 6 位、允许纯数字。 */
    private final boolean relaxedPassword;

    public InternalMemberController(OrgStore orgStore, PasswordHasher hasher,
                                    @Value("${psych.security.relaxed-operator-password:false}")
                                    boolean relaxedPassword) {
        this.orgStore = orgStore;
        this.hasher = hasher;
        this.relaxedPassword = relaxedPassword;
    }

    public record MemberView(String id, String name, String role, String roleLabel, String username,
                             boolean hasPassword, String createdAt, String revokedAt) {
    }

    public record CreateRequest(
            @NotBlank @Size(max = 64) String name,
            @NotBlank String role,
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Size(max = 128) String password
    ) {
    }

    public record PasswordRequest(@NotBlank @Size(max = 128) String password) {
    }

    public record Created(MemberView member, String token, String note) {
    }

    @GetMapping
    public List<MemberView> list() {
        String tenantId = tenantOf(null);
        return orgStore.members(tenantId).stream().map(m -> view(m)).toList();
    }

    /**
     * 建一个成员。口令直接由管理员指定并发给本人——不走"邀请邮件"，
     * 因为这个系统没有发信通道（见 /account 页的说明）。
     */
    @PostMapping
    public Created create(@Valid @RequestBody CreateRequest request,
                          @RequestAttribute(name = "sm.operator", required = false) Operator me) {
        String role = request.role().strip().toLowerCase();
        if (!ROLES.contains(role)) {
            throw new IllegalArgumentException("角色必须是 admin / operator / reviewer / viewer");
        }
        String username = request.username().strip();
        if (orgStore.credential(username).isPresent()) {
            throw new ConflictException("登录名已被占用：" + username);
        }
        checkPassword(request.password());

        String tenantId = tenantOf(me);
        String teamId = me == null ? null : me.teamId();
        OrgStore.Issued issued = orgStore.createMember(tenantId, teamId, request.name().strip(), role);
        orgStore.setCredential(issued.member().id(), username, hasher.hash(request.password()));
        return new Created(view(issued.member()), issued.token(),
                "口令请当面或私下转交；令牌只显示这一次，用于脚本调用");
    }

    @PostMapping("/{id}/revoke")
    public MemberView revoke(@PathVariable String id,
                             @RequestAttribute(name = "sm.operator", required = false) Operator me) {
        if (me != null && me.id().equals(id)) {
            // 把自己撤了，后台就没人能建人了；这种事应该由另一个管理员来做
            throw new IllegalArgumentException("不能撤销自己的账号");
        }
        requireMember(id);
        orgStore.revokeMember(id);
        orgStore.revokeSessionsOf(id);
        return view(requireMember(id));
    }

    @PostMapping("/{id}/password")
    public MemberView resetPassword(@PathVariable String id, @Valid @RequestBody PasswordRequest request) {
        OrgStore.Member member = requireMember(id);
        checkPassword(request.password());
        orgStore.credentialByMember(id)
                .orElseThrow(() -> new IllegalStateException("这个成员还没有登录名，先设一个"));
        orgStore.updatePassword(id, hasher.hash(request.password()));
        orgStore.bumpEpoch(id);
        orgStore.revokeSessionsOf(id);
        return view(member);
    }

    private OrgStore.Member requireMember(String id) {
        String tenantId = tenantOf(null);
        return orgStore.members(tenantId).stream()
                .filter(m -> m.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("成员不存在"));
    }

    private MemberView view(OrgStore.Member m) {
        String username = orgStore.usernameOf(m.id()).orElse(null);
        boolean hasPassword = orgStore.credentialByMember(m.id())
                .map(c -> c.passwordHash() != null && !c.passwordHash().isBlank())
                .orElse(false);
        return new MemberView(m.id(), m.name(), m.role(),
                new Operator(m.id(), m.tenantId(), m.teamId(), m.name(), m.role()).roleLabel(),
                username, hasPassword,
                m.createdAt() == null ? null : m.createdAt().toString(),
                m.revokedAt() == null ? null : m.revokedAt().toString());
    }

    private String tenantOf(Operator me) {
        if (me != null && me.tenantId() != null && !me.tenantId().isBlank()) return me.tenantId();
        return orgStore.tenants().stream().findFirst().map(OrgStore.Tenant::id)
                .orElseThrow(() -> new IllegalStateException("还没有租户，先启动一次让播种脚本建一个"));
    }

    /** 后台口令不允许纯数字、至少 8 位；演示档放宽到 6 位（与改口令同一个开关）。 */
    private void checkPassword(String raw) {
        if (relaxedPassword) {
            if (raw == null || raw.strip().length() < 6) {
                throw new IllegalArgumentException("口令至少 6 位");
            }
            return;
        }
        if (!PasswordHasher.acceptable(raw) || raw.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("口令至少 8 位，且不能是纯数字");
        }
    }
}
