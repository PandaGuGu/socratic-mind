package com.socraticmind.common.guard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 确定性校验：不调用模型，只做字符串判断。
 *
 * 三条规则都刻意选"误判可以接受、漏判不可以"的方向：
 * 误判的代价是退回模板（质量略降），漏判的代价是用户看到一句系统不该说的话。
 * 阈值全部可被运维在策略页调整，代码里不写死。
 */
public final class GuardRules {

    private static final Pattern NOISE =
            Pattern.compile("[\\p{Punct}\\s，。！？；：、“”‘’（）《》〈〉【】…—·]");

    /** 高频虚词组成的二连字不作为"内容"，否则任何中文句子的重合度都会虚高。 */
    private static final Set<String> STOP = Set.of(
            "的了", "是在", "我们", "你们", "他们", "这个", "那个", "就是", "不是", "可以",
            "一个", "什么", "因为", "所以", "但是", "如果", "已经", "还是", "这样", "那样",
            "自己", "然后", "现在", "时候", "觉得", "有点", "一点", "而且", "或者", "只是");

    private GuardRules() {
    }

    public static List<GuardViolation> inspect(String output, String source, ProfileRule rule) {
        List<GuardViolation> violations = new ArrayList<>();
        if (rule == null || !rule.enabled()) return violations;

        String text = output == null ? "" : output.strip();
        if (text.isEmpty()) {
            violations.add(new GuardViolation(GuardViolation.EMPTY, "输出为空", ""));
            return violations;
        }
        if (rule.minChars() > 0 && text.length() < rule.minChars()) {
            violations.add(new GuardViolation(GuardViolation.MIN_CHARS,
                    "短于 " + rule.minChars() + " 字（实际 " + text.length() + "）", snippet(text)));
        }
        if (rule.maxChars() > 0 && text.length() > rule.maxChars()) {
            violations.add(new GuardViolation(GuardViolation.MAX_CHARS,
                    "长于 " + rule.maxChars() + " 字（实际 " + text.length() + "）", snippet(text)));
        }
        for (String banned : rule.banned()) {
            if (banned == null || banned.isBlank()) continue;
            if (text.contains(banned)) {
                violations.add(new GuardViolation(GuardViolation.BANNED,
                        "命中禁句「" + banned + "」", banned));
            }
        }
        // 敏感词库：与上面的禁句分开，因为它的词表可以独立维护、分级。
        // 命中 BLOCK/MASK 都算"这句不能原样出去"——区别只在运维视角（拦 vs 遮），
        // 对模型来说是同一种反馈：重写一次，再不过就退回模板。
        for (SensitiveLexicon.Hit hit : SensitiveLexicon.get().scanOutput(text)) {
            if (hit.action() == SensitiveLexicon.Action.REVIEW) continue;
            violations.add(new GuardViolation(GuardViolation.LEXICON,
                    "命中敏感词「" + hit.word() + "」（" + hit.category() + "）", hit.word()));
        }
        if (rule.novelty() > 0 && source != null && !source.isBlank()) {            double score = noveltyScore(text, source);
            if (score < rule.novelty()) {
                violations.add(new GuardViolation(GuardViolation.NOVELTY,
                        String.format("与输入重合度 %.2f，低于阈值 %.2f", score, rule.novelty()),
                        snippet(text)));
            }
        }
        return violations;
    }

    /**
     * 输出中有多少"内容二连字"能在输入里找到。
     * 1.0 表示每个词组都来自输入，0 表示几乎全是新内容。
     * 这是启发式，不是事实核查：它的用途是拦住"凭空加戏"，不是判断真假。
     */
    public static double noveltyScore(String output, String source) {
        List<String> grams = grams(output);
        if (grams.isEmpty()) return 1.0;
        String haystack = normalize(source);
        int hit = 0;
        for (String gram : grams) {
            if (haystack.contains(gram)) hit++;
        }
        return (double) hit / grams.size();
    }

    private static List<String> grams(String text) {
        String normalized = normalize(text);
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i + 2 <= normalized.length(); i++) {
            String gram = normalized.substring(i, i + 2);
            if (STOP.contains(gram)) continue;
            out.add(gram);
        }
        return new ArrayList<>(out);
    }

    private static String normalize(String text) {
        return text == null ? "" : NOISE.matcher(text).replaceAll("");
    }

    private static String snippet(String text) {
        return text.length() <= 24 ? text : text.substring(0, 24) + "…";
    }
}
