package com.socraticmind.infrastructure.ai;

import com.socraticmind.common.port.ChatPort;
import com.socraticmind.common.support.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 桩生成器：不产出"自由发挥"的答案，而是从提示词里的证据段落中挑最相关的一句，附上引用编号。
 *
 * 它存在的意义有两个：
 * 1) 无 API key 时链路仍能端到端跑通（入库 → 检索 → 生成 → 校验）；
 * 2) 为引用校验节点提供一个"必定守规矩"的对照组——如果连它都校验不过，说明提示词契约或校验逻辑有问题。
 *
 * 输出带 modelId=stub-evidence-grounded，任何结果页面都必须标明这是桩输出。
 */
public class StubChatAdapter implements ChatPort {

    private static final Pattern EVIDENCE_BLOCK =
            Pattern.compile("\\[(\\d+)]\\s*(.+?)(?=\\n\\[\\d+]|\\n问|\\z)", Pattern.DOTALL);
    private static final Pattern QUESTION = Pattern.compile("问题[：:]\\s*(.+)");

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        String question = extractQuestion(userPrompt);
        List<int[]> unused = new ArrayList<>();
        List<String> candidates = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();

        Matcher m = EVIDENCE_BLOCK.matcher(userPrompt);
        while (m.find()) {
            numbers.add(Integer.parseInt(m.group(1)));
            candidates.add(m.group(2).trim());
        }
        unused.clear();

        if (candidates.isEmpty()) {
            return "我查到的资料不足以回答这一点。";
        }

        int bestIdx = 0;
        double bestScore = -1;
        for (int i = 0; i < candidates.size(); i++) {
            double score = TextUtils.lexicalScore(question, "", candidates.get(i));
            if (score > bestScore) {
                bestScore = score;
                bestIdx = i;
            }
        }

        String evidenceText = candidates.get(bestIdx);
        String sentence = pickSentence(evidenceText, question);
        int number = numbers.get(bestIdx);

        // 只输出正文；"这是桩"这件事由 modelId=stub-evidence-grounded 与 trace 承载，
        // 写进正文会污染引用覆盖率（它是一条没有引用的陈述）。
        return "根据资料：" + sentence + "[" + number + "]";
    }

    private static String extractQuestion(String userPrompt) {
        Matcher m = QUESTION.matcher(userPrompt);
        if (m.find()) return m.group(1).trim();
        String[] lines = userPrompt.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) return lines[i].trim();
        }
        return userPrompt;
    }

    private static String pickSentence(String block, String question) {
        // 证据块第一行是定位（标题 · 章节 · 页码），不是正文，必须剔掉
        String[] lines = block.split("\n", 2);
        String body = lines.length > 1 ? lines[1] : block;
        String[] parts = body.split("(?<=[。！？；])");
        String best = null;
        double bestScore = -1;
        for (String p : parts) {
            String s = p.trim();
            if (s.length() < 6) continue;
            double score = TextUtils.lexicalScore(question, "", s);
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        if (best == null) best = TextUtils.truncate(body, 120);
        return best;
    }

    @Override
    public String modelId() {
        return "stub-evidence-grounded";
    }
}
