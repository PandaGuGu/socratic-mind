package com.socraticmind.agent.cbt.safety;

import java.util.List;

/**
 * 内置默认规则集：与 v0.1.1 代码里的那套正则一一对应。
 *
 * 它只在配置文件缺失/损坏时兜底，同时是"最少规则数"校验的底线：
 * 自伤、伤人两条 hold 规则不许被删掉，也不许被关闭。
 */
public final class SafetyRuleDefaults {

    private SafetyRuleDefaults() {
    }

    public static SafetyRules rules() {
        return new SafetyRules(1, null, "system", defaultRules(), defaultCases());
    }

    private static List<SafetyRule> defaultRules() {
        return List.of(
                new SafetyRule("self_harm", "自我伤害", SafetyRule.HOLD,
                        "自杀|自尽|轻生|自残|自伤|伤害自己|不想活|活不下去|活着(?:没|太)意思|活着太累|"
                                + "结束(?:我的)?生命|了结自己|想死|去死|死了算了|不如死|想消失|不想(?:再)?醒来|"
                                + "撑不下去|跳楼|跳桥|卧轨|上吊|割腕|烧炭|吞药|服毒|"
                                + "kill myself|suicide|end my life|self[\\s-]?harm|want to die|cut myself",
                        true,
                        "命中即暂停并转介。删改这条会让真正的危机表达漏判，保存前会跑用例表。"),
                new SafetyRule("harm_others", "伤害他人", SafetyRule.HOLD,
                        "杀(?:了|掉)(?:他|她|它|人)|捅死|弄死(?:他|她|人)|报复所有|让他也死|"
                                + "kill (?:him|her|them)",
                        true,
                        "命中即暂停并转介。"),
                new SafetyRule("third_party", "指向他人", SafetyRule.CONTEXT,
                        "(?:他|她|朋友|同学|同事|室友|家人|妈妈|爸爸|母亲|父亲|孩子|弟弟|妹妹|哥哥|姐姐|"
                                + "有人|某人|患者|来访者|病人)[^，。！？；\\n]{0,10}"
                                + "(?:自杀|自残|自伤|想死|不想活|割腕|跳楼|结束生命|轻生)",
                        true,
                        "风险词前 10 字内出现第三人称/关系称谓：不暂停，转人工复核。"),
                new SafetyRule("quoted", "引用语境", SafetyRule.CONTEXT,
                        "歌词|电影|电视剧|剧中|小说|书里|主人公|新闻|报道|论文|研究表明|案例|段子|梗",
                        true,
                        "歌、影视、书、新闻、研究里提到的风险词：不暂停，转人工复核。"),
                new SafetyRule("first_person", "本人意图", SafetyRule.SIGNAL,
                        "我(?:也|真的|现在|已经|其实|最近|好|就是|自己|真|就)*"
                                + "(?:不想活|想死|自杀|自残|割腕|跳楼|撑不下去|想消失)",
                        true,
                        "同一句里既提到他人又表达了自己：压过上下文线索，直接暂停。"),
                new SafetyRule("idiom", "口语补语", SafetyRule.IGNORE,
                        "(?:笑|累|饿|困|热|冷|气|烦|忙|吓|急|尬|无聊|幸福|美|帅|可爱)死(?:了|我|你)|"
                                + "想死你了|死也不|打死不|死机|死循环|死磕|死党|死宅",
                        true,
                        "匹配前先抹掉，避免把\"累死了\"当自伤。")
        );
    }

    /** 出厂用例表：发布时的回放清单，后台可增删改，但至少要保留若干条。 */
    public static List<SafetyCase> defaultCases() {
        return List.of(
                new SafetyCase("我真的不想活了", SafetyCase.HOLD, "最直接的自伤表达"),
                new SafetyCase("我想死", SafetyCase.HOLD, "短句自伤表达"),
                new SafetyCase("最近总想着自杀", SafetyCase.HOLD, "含「自杀」一词"),
                new SafetyCase("我想割腕", SafetyCase.HOLD, "具体方式"),
                new SafetyCase("活着没意思，想消失", SafetyCase.HOLD, "间接表达"),
                new SafetyCase("我不想再醒来了", SafetyCase.HOLD, "间接表达"),
                new SafetyCase("真的撑不下去了", SafetyCase.HOLD, "间接表达"),
                new SafetyCase("I want to die", SafetyCase.HOLD, "英文表达（空格不能被压掉）"),
                new SafetyCase("kill myself", SafetyCase.HOLD, "英文表达"),
                new SafetyCase("我妈不想活了，我也不想活了", SafetyCase.HOLD, "本人意图优先于第三人称"),
                new SafetyCase("新闻里看到有人跳楼，我也不想活了", SafetyCase.HOLD, "引用语境不能压过本人意图"),
                new SafetyCase("我朋友说他不想活了", SafetyCase.NOT_PASS, "指向他人必须进复核"),
                new SafetyCase("同事说他最近总说想死", SafetyCase.NOT_PASS, "指向他人必须进复核"),
                new SafetyCase("书里主人公自杀了", SafetyCase.NOT_PASS, "引用语境必须进复核"),
                new SafetyCase("累死了，今天加班到十点", SafetyCase.PASS, "程度补语"),
                new SafetyCase("笑死我了", SafetyCase.PASS, "口语"),
                new SafetyCase("想死你了", SafetyCase.PASS, "亲昵用法"),
                new SafetyCase("他把我气死了", SafetyCase.PASS, "程度补语")
        );
    }
}
