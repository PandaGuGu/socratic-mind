package com.socraticmind.knowledge;

import com.socraticmind.common.model.Chunk;
import com.socraticmind.knowledge.chunk.ParentChildChunker;
import com.socraticmind.knowledge.parse.MarkdownParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkingTest {

    private final MarkdownParser parser = new MarkdownParser();
    private final ParentChildChunker chunker = new ParentChildChunker();

    @Test
    void sectionPathShouldNotRepeatDocumentTitle() {
        String md = """
                # 一本书
                ## 第三章 治疗实施
                ### 3.3 认知检验
                认知检验的目的是把结论转成可验证的预测。
                """;
        MarkdownParser.ParsedDocument doc = parser.parse("一本书", md);
        assertEquals(1, doc.sections().size());
        assertEquals("一本书", doc.sections().get(0).titlePath());
        assertEquals("第三章 治疗实施 > 3.3 认知检验", doc.sections().get(0).sectionPath());
    }

    @Test
    void recommendationAndConditionMustStayTogether() {
        String body = "对于成人焦虑障碍，推荐使用认知行为疗法作为一线心理干预。"
                + "该推荐不适用于伴有严重自伤风险且未稳定的个体。";
        List<MarkdownParser.Section> sections = List.of(
                new MarkdownParser.Section(2, "适用范围", "指南", "适用范围", body));

        ParentChildChunker.Result result = chunker.chunk("doc", "doc_v1", "zh", sections);
        List<Chunk> children = result.chunks().stream().filter(c -> !c.parent()).toList();

        assertEquals(1, children.size(), "一整段不应被拆开");
        assertTrue(children.get(0).content().contains("不适用于"), "否定条件必须与推荐在同一块");
    }

    @Test
    void parentChunkShouldCarrySectionContext() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append("这是第").append(i).append("段用于测试合并的正文内容，长度足够触发切分边界。\n\n");
        }
        List<MarkdownParser.Section> sections = List.of(
                new MarkdownParser.Section(2, "长节", "书", "长节", sb.toString()));

        ParentChildChunker.Result result = chunker.chunk("doc", "doc_v1", "zh", sections);
        List<Chunk> parents = result.chunks().stream().filter(Chunk::parent).toList();
        List<Chunk> children = result.chunks().stream().filter(c -> !c.parent()).toList();

        assertEquals(1, parents.size());
        assertTrue(children.size() > 1, "长正文应被切成多个子块");
        assertFalse(children.get(0).parentId() == null);
        assertEquals(parents.get(0).id(), children.get(0).parentId());
    }
}
