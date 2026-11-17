package com.socraticmind.knowledge.chunk;

import com.socraticmind.common.model.Chunk;
import com.socraticmind.common.support.Ids;
import com.socraticmind.knowledge.parse.MarkdownParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 父/子块切分。
 *
 * 三条硬约束（来自 docs/01-方案/04-RAG知识数据架构.md §4.4）：
 * 1. 只在段落边界切，绝不在句中断开——保证"推荐意见"与"适用条件"不被拆散；
 * 2. 表格/列表整体保留（以行首标记识别，不参与合并）；
 * 3. 每个子块保留父块引用与标题链，检索后可补上下文。
 */
public class ParentChildChunker {

    private static final int TARGET_CHARS = 260;
    private static final int MAX_CHARS = 420;

    public record Result(List<Chunk> chunks, int parentCount, int childCount) {
    }

    public Result chunk(String documentId, String versionId, String language, List<MarkdownParser.Section> sections) {
        List<Chunk> all = new ArrayList<>();
        int parentIndex = 0;
        int childIndex = 0;

        for (MarkdownParser.Section section : sections) {
            String parentId = Ids.of("pc");
            String parentContent = "# " + section.sectionPath() + "\n" + section.body();
            all.add(Chunk.parent(parentId, documentId, versionId, parentIndex++,
                    section.titlePath(), section.sectionPath(), parentContent, language));

            for (String paragraph : splitParagraphs(section.body())) {
                all.add(Chunk.child(Ids.of("c"), documentId, versionId, parentId, childIndex++,
                        section.titlePath(), section.sectionPath(), paragraph, null, null, language));
            }
        }

        int parents = (int) all.stream().filter(Chunk::parent).count();
        return new Result(all, parents, all.size() - parents);
    }

    /** 段落级切分：先按空行，再把过短的段落合并到目标长度；列表/表格块不合并。 */
    List<String> splitParagraphs(String body) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : body.split("\n")) {
            if (line.isBlank()) {
                if (!current.isEmpty()) {
                    blocks.add(current.toString().trim());
                    current.setLength(0);
                }
            } else {
                if (!current.isEmpty()) current.append('\n');
                current.append(line);
            }
        }
        if (!current.isEmpty()) blocks.add(current.toString().trim());

        List<String> merged = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String block : blocks) {
            boolean structural = looksStructural(block);
            if (structural) {
                if (!buffer.isEmpty()) {
                    merged.add(buffer.toString().trim());
                    buffer.setLength(0);
                }
                merged.add(block);
                continue;
            }
            if (buffer.length() + block.length() > MAX_CHARS && buffer.length() >= TARGET_CHARS / 2) {
                merged.add(buffer.toString().trim());
                buffer.setLength(0);
            }
            if (!buffer.isEmpty()) buffer.append("\n\n");
            buffer.append(block);
            if (buffer.length() >= TARGET_CHARS) {
                merged.add(buffer.toString().trim());
                buffer.setLength(0);
            }
        }
        if (!buffer.isEmpty()) merged.add(buffer.toString().trim());
        return merged;
    }

    private boolean looksStructural(String block) {
        String first = block.lines().findFirst().orElse("");
        return first.startsWith("|") || first.startsWith("- ") || first.startsWith("* ")
                || first.matches("^\\d+[.)、].*") || first.startsWith(">");
    }
}
