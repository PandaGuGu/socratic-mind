package com.socraticmind.rag.validation;

import com.socraticmind.common.model.Verification;
import com.socraticmind.common.support.TextUtils;
import com.socraticmind.rag.dto.EvidencePack;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用校验：规则实现，不调用模型。
 *
 * 检查两件事：
 * 1. 回答里出现的每个 [n] 都能对应到一条真实证据（编号有效性）；
 * 2. 事实性陈述是否带引用（覆盖率 + 未支撑清单）。
 *
 * 这是"可控"这条卖点的最后一道闸：模型说得再顺，编号对不上就是不合格。
 */
@Component
public class CitationVerifier {

    private static final Pattern REF = Pattern.compile("\\[(\\d+)]|【(\\d+)】");
    private static final Pattern SENTENCE =
            Pattern.compile("[^。！？\\n]+[。！？]?(?:\\s*[\\[【]\\d+[\\]】])*");
    private static final Pattern OFFER = Pattern.compile(
            "^(如果|你可以|要不要|需要我|建议你|欢迎|希望|不妨|你也可以|要不要我)");

    private static boolean isQuestion(String sentence) {
        return sentence.endsWith("？") || sentence.endsWith("?");
    }

    private static boolean isOffer(String sentence) {
        return OFFER.matcher(sentence).find();
    }

    /** 回答里实际引用到的编号（去重）。用于只回传被用到的引用，避免"带一堆没用上"的证据。 */
    public Set<Integer> referenced(String answer) {
        Set<Integer> numbers = new java.util.LinkedHashSet<>();
        if (answer == null) return numbers;
        Matcher m = REF.matcher(answer);
        while (m.find()) {
            numbers.add(Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2)));
        }
        return numbers;
    }

    public Verification verify(String answer, EvidencePack pack) {
        if (answer == null || answer.isBlank()) {
            return new Verification(false, 0.0, List.of(), List.of("回答为空"));
        }

        Set<Integer> validNumbers = new HashSet<>();
        for (int i = 1; i <= pack.items().size(); i++) validNumbers.add(i);

        List<Integer> invalid = new ArrayList<>();
        Matcher m = REF.matcher(answer);
        Set<Integer> referenced = new HashSet<>();
        while (m.find()) {
            int n = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
            if (!validNumbers.contains(n)) {
                invalid.add(n);
            } else {
                referenced.add(n);
            }
        }

        List<String> claims = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        int cited = 0;

        // 句末引用并入本句，下一句重新开始：这样「结论。[1] 展开。」会被算成 1 句带引用 + 1 句无引用
        Matcher sentenceMatcher = SENTENCE.matcher(answer);
        while (sentenceMatcher.find()) {
            String sentence = sentenceMatcher.group().trim();
            if (sentence.length() < 8) continue;
            if (sentence.startsWith("（") || sentence.startsWith("(")) continue;

            boolean hasRef = REF.matcher(sentence).find();
            if (hasRef) {
                claims.add(sentence);
                cited++;
                continue;
            }
            // 提问、邀请、建议不是"事实主张"，不该计入无依据陈述——
            // 否则一个主动追问的模型会被指标惩罚，等于鼓励它闭嘴。
            if (isQuestion(sentence) || isOffer(sentence)) continue;

            claims.add(sentence);
            unsupported.add(TextUtils.truncate(sentence, 40));
        }

        double coverage = claims.isEmpty() ? 0.0 : (double) cited / claims.size();
        boolean allValid = invalid.isEmpty() && !referenced.isEmpty();

        return new Verification(allValid, Math.round(coverage * 100) / 100.0, invalid, unsupported);
    }
}
