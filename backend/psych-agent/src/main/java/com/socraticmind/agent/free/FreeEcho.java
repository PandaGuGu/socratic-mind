package com.socraticmind.agent.free;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 没有真实对话模型时的"接话"。
 *
 * 它不是一个会聊天的东西，只是一组**按对方说了什么**挑出来的短句：
 * 先认出这是打招呼、是在问"你是不是人"、是在说累——再接住，而不是把所有输入都当成
 * "一件待剖析的心理事件"。早先那版是把四句模板按轮次轮播，于是"你好"会被回成
 * "最压着你的是哪一部分"，短句还会出现空的「」——那种机械感比沉默更伤人。
 *
 * 三条自我约束：
 * 1. 不是每句都追问。每三轮里有一轮只回应、不提问——一直问会变成审讯；
 * 2. 不在同一段对话里连着重用同一句；
 * 3. 没有可引用的内容就不硬引用，宁可只说一句"我在"。
 *
 * 接了真实模型之后，这条路径不再被调用（见 {@code FreeChatFlow#isStub}）。
 */
final class FreeEcho {

    private FreeEcho() {
    }

    private static final Pattern GREETING = Pattern.compile(
            "^(你好|您好|hi|hello|嗨|哈喽|在吗|在么|早|早上好|午安|下午好|晚上好)[\\s!！。,.，~～?？]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ABOUT_ME = Pattern.compile(
            "(你是人吗|你是真人|你是不是人|你是机器人|你是ai|你是AI|你是程序|你是谁)");
    private static final Pattern THANKS = Pattern.compile("^(谢谢|多谢|谢了|thanks|thx|辛苦了)[\\s!！。,.，~～]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BYE = Pattern.compile("^(晚安|再见|拜拜|bye|先这样|下次聊)[\\s!！。,.，~～]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PUNCT = Pattern.compile("[，。！？、；：,.!?;:…\\s\"'“”‘’]+");
    /** 自我否定：先把它指出来，而不是顺着问"为什么这么想"。 */
    private static final Pattern SELF_DOUBT = Pattern.compile(
            "(没用|不行|废物|失败|不够好|很差劲|讨厌自己|看不起自己|配不上|很蠢|很笨)");

    /** 情绪词 → 一句"接住它"的话。只接住，不解释，也不诊断。 */
    private static final String[][] EMOTIONS = {
            {"累", "乏", "撑不住", "累了就先说累，不用先找一个说得过去的理由。"},
            {"难受", "难过", "心里堵", "嗯。难受的时候，不用急着讲道理。"},
            {"焦虑", "慌", "紧张", "害怕", "慌的时候，先感觉一下脚底下踩着的东西。"},
            {"睡不着", "没睡着", "没睡好", "睡不好", "失眠", "睡不着的人往往不是不困，是脑子不肯停。"},
            {"委屈", "想哭", "委屈就说委屈，这儿不用端着。"},
            {"心里空", "空落落", "空虚", "空着也是一种状态，不用马上把它填满。"},
            {"没意思", "麻木", "提不起劲", "没意思的时候，不用硬找意义。"},
            {"烦", "生气", "气死", "生气是有理由的，先说那个理由。"}
    };

    /** 在讲一件具体的事：先问那一刻的细节，比问"最卡住你的是什么"更像在听。 */
    private static final Pattern EVENT = Pattern.compile(
            "(的时候|那会儿|那次|当时|昨天|今天|上周|刚才|开会|上班|下班|会议|面试|回家)");
    private static final String[] EVENT_Q = {
            "那时候，你脑子里冒出来的第一句话是什么？",
            "那一刻身体有什么反应？",
            "旁边有人吗？",
            "后来你是怎么从那儿走开的？"
    };

    private static final String[] QUESTIONS = {
            "那件事里，最让你卡住的是哪一刻？",
            "当时你人在哪儿？",
            "这句话，你跟别人说过吗？",
            "如果它有个形状，会是什么样？",
            "你现在身上哪儿最紧？",
            "这两天有没有哪一小段，它是轻一点的？",
            "你希望它最后变成什么样？"
    };

    private static final String[] PLAIN = {
            "我先不给建议，你慢慢说。",
            "能说出来，已经花了不少力气。",
            "听上去不是一句两句能说完的。",
            "嗯。我在。",
            "这事搁了挺久了吧。"
    };

    static String reply(String raw, int turn, List<String> recent) {
        String text = raw == null ? "" : raw.strip();
        String plain = PUNCT.matcher(text).replaceAll("");

        if (plain.isEmpty()) {
            return vary(turn, recent, "嗯，我在。", "不急，想到什么说什么。", "我在。");
        }
        if (GREETING.matcher(text).matches()) {
            return vary(turn, recent, "在。不用急着说正事。", "在的。今天怎么样？");
        }
        if (ABOUT_ME.matcher(text).find()) {
            return "不是人，是个陪你说话的程序。你要是不想跟程序说，也很正常。";
        }
        if (THANKS.matcher(text).matches()) {
            return vary(turn, recent, "不用谢。我在这儿。");
        }
        if (BYE.matcher(text).matches()) {
            return vary(turn, recent, "嗯，晚安。", "好，那先这样。");
        }

        java.util.regex.Matcher doubt = SELF_DOUBT.matcher(plain);
        if (doubt.find()) {
            return "「" + doubt.group(1) + "」这个词，是你自己下的，还是别人说的？";
        }

        String emotion = emotionOf(plain);
        String quote = quoteOf(plain);
        int seed = Math.abs((plain + turn).hashCode());

        // 太短、只够接住一句的：不追问
        if (emotion != null && quote == null) return emotion;

        // 每三轮里有一轮只回应、不提问
        if (turn % 3 == 2) {
            return emotion != null ? emotion : pick(PLAIN, seed, recent);
        }

        if (EVENT.matcher(plain).find()) {
            return pick(EVENT_Q, seed, recent);
        }

        String question = pick(QUESTIONS, seed, recent);
        if (emotion != null) {
            return turn % 2 == 0 ? emotion : emotion + " " + question;
        }
        if (quote != null && turn % 2 == 0) {
            return "「" + quote + "」。" + question;
        }
        return question;
    }

    private static String emotionOf(String plain) {
        for (String[] row : EMOTIONS) {
            for (int i = 0; i < row.length - 1; i++) {
                if (plain.contains(row[i])) return row[row.length - 1];
            }
        }
        return null;
    }

    /** 只引一句像样的话：至少 6 个字，截到第一个标点或 16 字。太短、或者是问句，都不引。 */
    private static String quoteOf(String plain) {
        if (plain.length() < 6) return null;
        // 问句本来就在等回答，再把它加引号抛回去会很像复读机
        if (plain.contains("？") || plain.contains("?") || plain.contains("是不是") || plain.contains("吗")) {
            return null;
        }
        String cut = plain;
        for (String stop : new String[]{"，", "。", "！", "？", ",", "!", "?", "；", ";"}) {
            int at = cut.indexOf(stop);
            if (at >= 4) { cut = cut.substring(0, at); break; }
        }
        if (cut.length() > 16) cut = cut.substring(0, 16);
        cut = cut.strip();
        return cut.length() >= 4 ? cut : null;
    }

    /** 从一组里挑一句；和最近三句重复就顺延，避免"同一句话又说了一遍"。 */
    private static String pick(String[] pool, int seed, List<String> recent) {
        for (int i = 0; i < pool.length; i++) {
            String candidate = pool[(seed + i) % pool.length];
            if (recent == null || !recent.contains(candidate)) return candidate;
        }
        return pool[seed % pool.length];
    }

    private static String vary(int turn, List<String> recent, String... options) {
        return pick(options, Math.abs(turn * 31 + options.length), recent);
    }
}
