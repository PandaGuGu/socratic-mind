package com.socraticmind.rag;

import com.socraticmind.common.model.Chunk;
import com.socraticmind.common.model.Citation;
import com.socraticmind.common.model.EvidenceItem;
import com.socraticmind.common.model.Verification;
import com.socraticmind.rag.dto.EvidencePack;
import com.socraticmind.rag.dto.PackItem;
import com.socraticmind.rag.validation.CitationVerifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitationVerifierTest {

    private final CitationVerifier verifier = new CitationVerifier();

    private EvidencePack packOf(int evidenceCount) {
        List<PackItem> items = new java.util.ArrayList<>();
        for (int i = 1; i <= evidenceCount; i++) {
            Chunk chunk = new Chunk("c" + i, "doc", "doc_v1", "p1", i, "书", "章节" + i,
                    "内容" + i, null, null, null, "zh", false);
            Citation citation = new Citation(i, "ev" + i, "c" + i, "doc", "书", "来源", null, "章节" + i, null);
            items.add(new PackItem(citation, EvidenceItem.plain("ev" + i, "c" + i, "approved"), chunk, null));
        }
        return new EvidencePack("问题", items, true, "ok");
    }

    @Test
    void validCitationsPass() {
        Verification v = verifier.verify("认知模型分三层：自动思维、中间信念、核心信念。[1]", packOf(2));
        assertTrue(v.allCitationsValid());
        assertEquals(1.0, v.citationCoverage());
        assertTrue(v.unsupportedClaims().isEmpty());
    }

    @Test
    void citationNumberOutsidePackIsRejected() {
        Verification v = verifier.verify("这是有出处的一句话。[5]", packOf(2));
        assertFalse(v.allCitationsValid());
        assertEquals(List.of(5), v.invalidCitationNums());
    }

    @Test
    void uncitedClaimIsReported() {
        Verification v = verifier.verify("自动思维是最表层的认知。[1] 它通常与具体情境绑定，出现很快。", packOf(2));
        assertTrue(v.allCitationsValid());
        assertEquals(0.5, v.citationCoverage());
        assertEquals(1, v.unsupportedClaims().size());
    }

    @Test
    void questionAndOfferAreNotTreatedAsClaims() {
        Verification v = verifier.verify(
                "认知模型分三层：自动思维、中间信念、核心信念。[1]\n"
                        + "如果你愿意，可以说说具体场景，我帮你分辨哪句是自动思维。\n"
                        + "你想从哪个例子开始？", packOf(2));
        assertTrue(v.allCitationsValid());
        assertEquals(1.0, v.citationCoverage(), "提问与邀请不该拉低引用覆盖率");
        assertTrue(v.unsupportedClaims().isEmpty());
    }

    @Test
    void parentheticalDisclaimerIsNotCountedAsClaim() {
        Verification v = verifier.verify("认知模型分三层。[1]\n（这是桩生成器的输出，仅用于验证链路。）", packOf(1));
        assertTrue(v.allCitationsValid());
        assertEquals(1.0, v.citationCoverage());
    }
}
