package com.socraticmind.app;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词是资产，不是代码注释：被改动时必须有人看见。
 *
 * 这里锁的是结构与硬约束，不是 SHA-256 全文哈希——哈希会在每次合理改写时误报，
 * 而"少了某个阶段""多了感叹号""混进 CRLF"才是真正会伤到流程的改动。
 */
class PromptAssetTest {

    private static final List<String> STAGES = List.of(
            "problem_identification",
            "cognitive_extraction",
            "cognitive_review",
            "intervention_decision",
            "action_confirm",
            "action_review",
            "behavioral_experiment",
            "practical_problem_solving",
            "safety_hold");

    private String asset() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/prompts/cbt/stages.md")) {
            assertNotNull(in, "提示词资产必须随 psych-agent 打进 classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyStageHasItsOwnAnchor() throws Exception {
        String text = asset();
        for (String stage : STAGES) {
            assertTrue(text.contains("[stage:" + stage + "]"), "缺少阶段提示词：" + stage);
        }
        long anchors = text.lines().filter(l -> l.startsWith("[stage:")).count();
        assertEquals(STAGES.size(), anchors, "锚点数量必须与阶段数一致，避免漏改或重复");
    }

    @Test
    void placeholdersSurviveEditing() throws Exception {
        String text = asset();
        assertTrue(text.contains("{{thought}}"), "认知检验必须回引用户原话");
        assertTrue(text.contains("{{plan}}"), "确认环节必须复述行动计划");
    }

    @Test
    void safetySectionKeepsAnExecutablePath() throws Exception {
        String text = asset();
        int from = text.indexOf("[stage:safety_hold]");
        assertTrue(from > 0);
        String safety = text.substring(from);
        assertTrue(safety.contains("所在地区"), "转介必须给可执行路径，不能只说「请寻求帮助」");
        assertTrue(safety.contains("急救") || safety.contains("心理援助"), "要指向当地求助渠道");
        assertFalse(safety.matches("(?s).*\\d{3,}.*"), "不写死某一地区的电话号码");
        assertTrue(safety.contains("不会追问"), "边界话术：不追问细节、不判断");
    }

    @Test
    void assetStaysLfAndCalm() throws Exception {
        String text = asset();
        assertFalse(text.contains("\r"), "提示词资产必须是 LF，Windows 检出后不应产生整文件 diff");
        assertFalse(text.contains("！"), "全套文案不用感叹号（docs/05-规范/02-UI设计规范.md §7）");
        assertFalse(text.toLowerCase().contains("加油"), "不做安慰性套话");
    }

    /**
     * 共享前言是"禁止清单"，不是人格设定：四条硬边界缺一条，模型就有了越界的入口。
     * 它被打进 psych-agent 的 classpath，CbtFlow 启动时读取，缺文件会直接启动失败。
     */
    @Test
    void counselorPromptKeepsHardBoundaries() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/prompts/cbt/counselor.md")) {
            assertNotNull(in, "共享前言必须随 psych-agent 打包");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.contains("不诊断"), "缺诊断边界");
            assertTrue(text.contains("不用药"), "缺用药边界");
            assertTrue(text.contains("不承诺疗效"), "缺疗效边界");
            assertTrue(text.contains("不制造依赖"), "缺依赖边界");
            assertFalse(text.contains("！"), "前言同样不用感叹号");
            assertFalse(text.contains("\r"));
        }
    }
}
