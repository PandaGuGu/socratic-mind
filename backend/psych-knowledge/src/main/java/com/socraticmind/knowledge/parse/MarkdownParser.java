package com.socraticmind.knowledge.parse;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 解析：按标题层级切成 Section，并保留标题链。
 * 第一版只支持 Markdown/纯文本；PDF/DOCX 待接入 RAGFlow DeepDoc 或 Tika（见 docs/03-设计/04-界面与交互.md 之外的入库待办）。
 */
public class MarkdownParser {

    public record Section(int level, String heading, String titlePath, String sectionPath, String body) {
    }

    public record ParsedDocument(String title, List<Section> sections) {
    }

    public ParsedDocument parse(String fallbackTitle, String raw) {
        List<Section> sections = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return new ParsedDocument(fallbackTitle, sections);
        }

        String[] lines = raw.replace("\r\n", "\n").split("\n");
        String docTitle = fallbackTitle;
        List<String[]> stack = new ArrayList<>(); // [level, heading]
        StringBuilder body = new StringBuilder();
        String currentHeading = "";
        int currentLevel = 0;

        for (String line : lines) {
            if (line.startsWith("#")) {
                flush(sections, stack, currentLevel, currentHeading, body);
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') level++;
                String heading = line.substring(level).trim();
                if (docTitle == null || docTitle.isBlank()) docTitle = heading;
                if (level == 1 && (stack.isEmpty())) {
                    docTitle = heading;
                }
                while (!stack.isEmpty() && Integer.parseInt(stack.get(stack.size() - 1)[0]) >= level) {
                    stack.remove(stack.size() - 1);
                }
                stack.add(new String[]{String.valueOf(level), heading});
                currentLevel = level;
                currentHeading = heading;
            } else {
                body.append(line).append('\n');
            }
        }
        flush(sections, stack, currentLevel, currentHeading, body);

        return new ParsedDocument(docTitle == null || docTitle.isBlank() ? fallbackTitle : docTitle, sections);
    }

    private void flush(List<Section> out, List<String[]> stack, int level, String heading, StringBuilder body) {
        String content = body.toString().trim();
        body.setLength(0);
        if (content.isEmpty()) return;

        String titlePath = stack.isEmpty() ? "" : stack.get(0)[1];
        StringBuilder sectionPath = new StringBuilder();
        // 跳过根级标题：文档标题已经由 titlePath 承载，再拼一次会得到「书名 · 书名 > 章节」
        for (int i = 1; i < stack.size(); i++) {
            if (sectionPath.length() > 0) sectionPath.append(" > ");
            sectionPath.append(stack.get(i)[1]);
        }
        if (sectionPath.length() == 0 && !stack.isEmpty()) {
            sectionPath.append(stack.get(stack.size() - 1)[1]);
        }
        out.add(new Section(level, heading, titlePath, sectionPath.toString(), content));
    }
}
