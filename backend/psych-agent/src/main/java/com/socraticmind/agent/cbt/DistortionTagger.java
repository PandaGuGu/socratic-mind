package com.socraticmind.agent.cbt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 从"自动思维"里标出**候选**认知偏差。
 *
 * 三条边界，写在这里免得后面被误用：
 * 1. 是候选，不是判定。输出只用于"提示可以往哪个方向看"，不写进总结、不告诉用户
 *    "你有灾难化思维"——那是诊断口吻，产品边界不允许。
 * 2. 只跑用户自己写下的那句话，不推断上下文。命中靠词表与句式，逻辑可复核。
 * 3. 最多返回 3 个，按固定顺序。全返回等于没重点，而 CBT 一次只检验一个想法。
 *
 * 词表是故意"笨"的：宁可漏（用户自己会说），不可乱（把"我必须去上班"标成应该式，
 * 反而干扰检验）。所以每条规则都要求一个相对明确的句式，而不是单个虚词。
 */
public final class DistortionTagger {

    /** 偏差名 → 触发句式。顺序即优先级。 */
    private static final Map<String, List<Pattern>> RULES = build();

    private static Map<String, List<Pattern>> build() {
        Map<String, List<Pattern>> m = new java.util.LinkedHashMap<>();

        m.put("灾难化", List.of(
                Pattern.compile("(一定|肯定|必然|注定)(会)?(完蛋|搞砸|失败|被开除|被拒绝|做不好)"),
                Pattern.compile("(全完|毁了|没救了|来不及了|彻底(完|废))"),
                Pattern.compile("(永远|再也)(不会|不能|好不了|改不了)")));

        m.put("以偏概全", List.of(
                Pattern.compile("(总是|每次|从来(都)?(不|没)|一直(都)?(不|没))"),
                Pattern.compile("(所有人|大家|没有人|谁)(都)?(觉得|认为|讨厌|看不起)")));

        m.put("非黑即白", List.of(
                Pattern.compile("(要么|不是).{0,12}(就是|而是)"),
                Pattern.compile("(必须|只能)(做到)?(完美|最好|第一)"),
                Pattern.compile("(一无是处|一文不值|什么都不行)")));

        m.put("读心", List.of(
                Pattern.compile("(他们|别人|大家|同事|领导)(一定|肯定|应该)(觉得|认为|在笑|看不起)"),
                Pattern.compile("(肯定|一定)(看出来|觉得我|在背后)")));

        m.put("算命", List.of(
                Pattern.compile("(迟早|早晚)(会|要)"),
                Pattern.compile("(肯定|一定)(不会|通不过|轮不到我)")));

        m.put("应该式", List.of(
                Pattern.compile("(我|他|她|他们)(应该|必须|本来就该)"),
                Pattern.compile("(不得不|不能不做|必须得)")));

        m.put("个人化", List.of(
                Pattern.compile("(都怪我|是我的错|因为我(才|所以)|是我害)"),
                Pattern.compile("(我(要|再)?(是)?(不|没).{0,8}(就|才)(会|导致))")));

        m.put("情绪化推理", List.of(
                Pattern.compile("(我觉得|我感觉).{0,20}(所以|说明|肯定)"),
                Pattern.compile("(心里没底|感觉不对).{0,10}(一定|说明)")));

        m.put("贴标签", List.of(
                Pattern.compile("(我(就)?是(个)?)(废物|失败者|loser|多余的人|累赘)"),
                Pattern.compile("(我)(真|太)?(没用|无能|愚蠢|很差劲)")));

        // 必须保持声明顺序：Map.copyOf 不保证顺序，标签的优先级会随机变化。
        return java.util.Collections.unmodifiableMap(m);
    }

    private DistortionTagger() {
    }

    public static List<String> tag(String thought) {
        if (thought == null || thought.isBlank()) return List.of();
        Set<String> found = new LinkedHashSet<>();
        for (Map.Entry<String, List<Pattern>> entry : RULES.entrySet()) {
            for (Pattern p : entry.getValue()) {
                if (p.matcher(thought).find()) {
                    found.add(entry.getKey());
                    break;
                }
            }
            if (found.size() >= 3) break;
        }
        return new ArrayList<>(found);
    }
}
