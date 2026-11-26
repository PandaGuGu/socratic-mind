package com.socraticmind.app;

import com.socraticmind.agent.dto.SearchResult;
import com.socraticmind.agent.knowledge.KnowledgeFlow;
import com.socraticmind.app.security.InternalAuthInterceptor;
import com.socraticmind.app.web.KnowledgeController;
import com.socraticmind.common.model.KnowledgeDocument;
import com.socraticmind.common.model.Operator;
import com.socraticmind.common.port.KnowledgeStore;
import com.socraticmind.common.port.OrgStore;
import com.socraticmind.knowledge.dto.IngestCommand;
import com.socraticmind.knowledge.service.IngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组织模式：令牌换身份、身份决定可见范围、可见范围只能是真实存在的团队。
 *
 * 这一组测的不是"功能有没有"，而是"越权路径有没有被堵上"：
 * 让调用方自己声明 scopes、或者往 ACL 里写一个不存在的团队 id，
 * 在字符串世界里都不会报错，只会静默地"谁都看不见"或"谁都看得见"。
 */
@SpringBootTest(properties = {"psych.org.enabled=true"})
class OrgAccessTest {

    @Autowired
    OrgStore orgStore;

    @Autowired
    KnowledgeController controller;

    @Autowired
    KnowledgeFlow flow;

    @Autowired
    IngestionService ingestion;

    @Autowired
    KnowledgeStore store;

    private record Fixture(Operator op, String teamId, String tenantId) {
    }

    /** 每次建一套独立的租户/团队，避免与其他用例抢同一个 scope。 */
    private Fixture org(String tenantName, String teamName, String memberName) {
        var tenant = orgStore.createTenant(tenantName);
        var team = orgStore.createTeam(tenant.id(), teamName);
        var issued = orgStore.createMember(tenant.id(), team.id(), memberName, Operator.REVIEWER);
        var op = orgStore.findByToken(issued.token()).orElseThrow();
        return new Fixture(op, team.id(), tenant.id());
    }

    private String scopedDoc(String title, String teamId) {
        var result = ingestion.ingest(new IngestCommand("acl", title, "测试来源", null, "zh", true,
                "# " + title + "\n\n" + title + "强调把问题与人分开，用外化的语言描述困扰。"));
        store.updateDocumentStatus(result.documentId(), KnowledgeDocument.PUBLISHED);
        store.updateAcl(result.documentId(), "team:" + teamId);
        return result.documentId();
    }

    @Test
    void tokenResolvesToOperatorAndRevocationTakesEffect() {
        var tenant = orgStore.createTenant("某医院");
        var team = orgStore.createTeam(tenant.id(), "心理科");
        var issued = orgStore.createMember(tenant.id(), team.id(), "李医生", Operator.REVIEWER);

        Operator found = orgStore.findByToken(issued.token()).orElseThrow();
        assertEquals("李医生", found.name());
        assertEquals(team.id(), found.teamId());
        assertTrue(found.canReview(), "审核者可以签字");
        assertTrue(found.scopes().contains("team:" + team.id()));
        assertTrue(found.scopes().contains("tenant:" + tenant.id()));

        assertTrue(orgStore.revokeMember(issued.member().id()));
        assertTrue(orgStore.findByToken(issued.token()).isEmpty(), "吊销后令牌立刻失效");
    }

    @Test
    void aclRejectsTeamsOutsideTheTenant() {
        Fixture mine = org("甲机构", "甲科", "甲医生");
        Fixture other = org("乙机构", "乙科", "乙医生");
        String docId = scopedDoc("叙事疗法", mine.teamId());

        var ok = controller.acl(docId,
                new KnowledgeController.AclRequest(List.of("team:" + mine.teamId())), mine.op());
        assertEquals(200, ok.getStatusCode().value());

        var crossTenant = controller.acl(docId,
                new KnowledgeController.AclRequest(List.of("team:" + other.teamId())), mine.op());
        assertEquals(400, crossTenant.getStatusCode().value(),
                "不能把文档挂到别的租户的团队上——那会让另一个机构的人看到它");

        var freeform = controller.acl(docId,
                new KnowledgeController.AclRequest(List.of("team:cbt")), mine.op());
        assertEquals(400, freeform.getStatusCode().value(),
                "自由字符串必须被拒绝，否则校验形同虚设");
    }

    @Test
    void identityDecidesWhatIsRetrievable() {
        Fixture mine = org("丙机构", "丙科", "丙医生");
        Fixture sibling = org("丙机构二部", "丁科", "丁医生");
        String docId = scopedDoc("外化对话", mine.teamId());

        SearchResult asOwner = flow.search("外化对话是什么", 5, mine.op().scopes());
        assertTrue(asOwner.hits().stream().anyMatch(h -> docId.equals(h.documentId())),
                "本团队的人应当检索得到");

        SearchResult asOther = flow.search("外化对话是什么", 5, sibling.op().scopes());
        assertFalse(asOther.hits().stream().anyMatch(h -> docId.equals(h.documentId())),
                "别的团队检索不到——范围来自身份，不是请求参数");
    }

    @Test
    void reviewWithoutOperatorIsRefusedWhenOrgModeIsOn() {
        Fixture mine = org("戊机构", "戊科", "戊医生");
        String docId = scopedDoc("合作对话", mine.teamId());

        var anonymous = controller.review(docId,
                new KnowledgeController.ReviewRequest("approve", "随便写的名字", null), null);
        assertEquals(401, anonymous.getStatusCode().value(),
                "组织模式下不接受自报姓名：署名必须来自令牌");

        var signed = controller.review(docId,
                new KnowledgeController.ReviewRequest("approve", null, "审过"), mine.op());
        assertEquals(200, signed.getStatusCode().value());
        assertNotNull(signed.getBody());
        @SuppressWarnings("unchecked")
        var review = (com.socraticmind.app.review.KnowledgeReviewStore.DocReview) signed.getBody().get("review");
        assertEquals("戊医生", review.operatorName(), "署名应当是令牌解析出来的名字");
        assertEquals(mine.op().id(), review.operatorId());
    }

    @Test
    void anonymousCallerOnlySeesPublicDocuments() {
        Fixture mine = org("己机构", "己科", "己医生");
        String docId = scopedDoc("焦点解决", mine.teamId());

        var body = controller.chat(new KnowledgeController.ChatRequest("焦点解决是什么", null), null);
        boolean cited = body.citations().stream().anyMatch(c -> docId.equals(c.documentId()));
        assertFalse(cited, "组织模式下匿名提问不能引用受限文档——null 范围等于不过滤，这是最容易漏的口子");

        var signed = controller.chat(new KnowledgeController.ChatRequest("焦点解决是什么", null), mine.op());
        assertTrue(signed.citations().stream().anyMatch(c -> docId.equals(c.documentId())),
                "本团队的人提问应当能引用到");
    }

    @Test
    void operatorAttributeNameIsStable() {
        // 控制器与拦截器靠这个属性名通信；改名会让所有接口静默变成"无身份"。
        assertEquals("sm.operator", InternalAuthInterceptor.OPERATOR_ATTR);
    }
}
