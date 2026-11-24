package com.socraticmind.agent.cbt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 偏差标签只做"候选"，所以两头都要测：
 * 该认出来的要认出来，普通叙述不能被贴标签。
 */
class DistortionTaggerTest {

    @Test
    void catchesClassicPatterns() {
        assertTrue(DistortionTagger.tag("我肯定会被开除，一切都完了").contains("灾难化"));
        assertTrue(DistortionTagger.tag("每次汇报我都会搞砸").contains("以偏概全"));
        assertTrue(DistortionTagger.tag("他们一定觉得我很蠢").contains("读心"));
        assertTrue(DistortionTagger.tag("我就是个废物").contains("贴标签"));
        assertTrue(DistortionTagger.tag("我应该每天都高效").contains("应该式"));
        assertTrue(DistortionTagger.tag("都怪我没做好").contains("个人化"));
    }

    @Test
    void leavesPlainNarrationAlone() {
        assertEquals(List.of(), DistortionTagger.tag("明天上午十点开会"));
        assertEquals(List.of(), DistortionTagger.tag("最近睡得不太好"));
        assertEquals(List.of(), DistortionTagger.tag(null));
    }

    @Test
    void capsAtThreeAndKeepsOrder() {
        List<String> tags = DistortionTagger.tag("我肯定会被开除，我总是搞砸，他们一定觉得我蠢，都怪我");
        assertTrue(tags.size() <= 3, "一次只检验一个方向，最多给三个候选");
        assertEquals("灾难化", tags.get(0), "顺序固定，便于回归");
    }
}
