package com.socraticmind.common.guard;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 敏感词库：分词表 + 归一化 + 分级动作。
 *
 * 它只回答两件事："这句话里有没有库里的词"、"命中的词该拦、该遮还是该放到复核"。
 * 不做语义判断，也不处理自伤风险——那是 {@code SafetyScreener} 的职责，两者故意分开：
 * 把"我想死"降级成一个违禁词，等于把危机转介换成了拦截提示。
 *
 * 归一化的顺序是有讲究的：
 * 1. NFKC：全角转半角，"ＱＱ"与"qq"要能对上；
 * 2. 去零宽与格式字符：粘贴来的文本常夹 U+200B；
 * 3. 只保留字母、数字、汉字：标点、空格、表情全部丢掉，于是"加 微·信"等于"加微信"；
 * 4. 压缩连续重复：对抗"加加加微信"这种最低成本的绕过；
 * 5. 英文小写。
 *
 * 代价是会有误判（"心理学"里的"理"当然不会命中，但短词可能），所以动作分三级，
 * block 只给拉客/导流这类"误判也不心疼"的词。误拦一句正常倾诉，比放过一条招生广告更糟。
 */
public final class SensitiveLexicon {

    public enum Action {
        BLOCK, MASK, REVIEW
    }

    public enum Scope {
        IN, OUT, BOTH
    }

    public record Entry(Scope scope, String category, Action action, String word, String normalized) {

        /**
         * 后台录入时用这个：归一化只能由词条本身算出来，不能由调用方填。
         * 否则页面上传一个"normalized 与 word 不一致"的词条，扫描结果就会和人对不上。
         */
        public static Entry of(Scope scope, String category, Action action, String word) {
            String w = word == null ? "" : word.strip();
            return new Entry(scope, category == null ? "" : category.strip(), action, w, normalize(w).value());
        }
    }

    public record Hit(String category, String word, Action action) {
    }

    private static final String RESOURCE = "/lexicon/sensitive.txt";

    /** 随包分发的基线：后台没保存过词库时用它，也是"恢复默认"的来源。 */
    private static final List<Entry> BUILT_IN = List.copyOf(load());

    /**
     * 当前生效的词库。
     *
     * 从 final 常量改成可替换的静态引用，是为了让后台改词不必发版（v0.6.0）。
     * 扫描路径保持无锁：替换是一个 volatile 写，读到的要么是旧表、要么是新表，
     * 不会出现"改到一半"的词库——保存失败时旧表继续生效。
     */
    private static volatile SensitiveLexicon current = new SensitiveLexicon(BUILT_IN);

    private final List<Entry> entries;

    SensitiveLexicon(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static SensitiveLexicon get() {
        return current;
    }

    public static List<Entry> builtIn() {
        return BUILT_IN;
    }

    /**
     * 安装一份新词库。只由 {@code LexiconStore} 在"文件加载成功 / 保存校验通过"后调用。
     * 词条在这里重新归一化：文件是手写或后台提交的，normalized 不可信。
     */
    public static void install(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("词库不能为空");
        }
        List<Entry> rebuilt = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            rebuilt.add(Entry.of(e.scope(), e.category(), e.action(), e.word()));
        }
        current = new SensitiveLexicon(rebuilt);
    }

    /** 用户输入侧：只跑 in/both 的词。 */
    public List<Hit> scanInput(String text) {
        return scan(text, true);
    }

    /** 模型输出侧：只跑 out/both 的词。 */
    public List<Hit> scanOutput(String text) {
        return scan(text, false);
    }

    private List<Hit> scan(String text, boolean input) {
        if (text == null || text.isBlank()) return List.of();
        Normalized n = normalize(text);
        if (n.value.isEmpty()) return List.of();

        Set<String> seen = new LinkedHashSet<>();
        List<Hit> hits = new ArrayList<>();
        for (Entry e : entries) {
            boolean applies = e.scope() == Scope.BOTH
                    || (input ? e.scope() == Scope.IN : e.scope() == Scope.OUT);
            if (!applies) continue;
            if (!contains(n.value, e.normalized())) continue;
            if (!seen.add(e.category() + "|" + e.word())) continue;
            hits.add(new Hit(e.category(), e.word(), e.action()));
        }
        return hits;
    }

    public boolean blocked(String text) {
        return scanInput(text).stream().anyMatch(h -> h.action() == Action.BLOCK);
    }

    /**
     * 输出侧打码：把命中的词替换成等长的 *，保留句子结构，
     * 让"重写"有上下文可依，而不是整段丢掉。
     */
    public String mask(String text) {
        if (text == null || text.isBlank()) return text;
        Normalized n = normalize(text);
        StringBuilder out = new StringBuilder(text);
        for (Entry e : entries) {
            if (e.scope() == Scope.IN) continue;
            int from = 0;
            while (true) {
                int idx = n.value.indexOf(e.normalized(), from);
                if (idx < 0) break;
                int start = n.index[idx];
                int end = n.index[idx + e.normalized().length() - 1] + 1;
                for (int i = start; i < end && i < out.length(); i++) {
                    out.setCharAt(i, '*');
                }
                from = idx + e.normalized().length();
            }
        }
        return out.toString();
    }

    public List<Entry> entries() {
        return entries;
    }

    /* ---------- 归一化 ---------- */

    private record Normalized(String value, int[] index) {
    }

    static Normalized normalize(String text) {
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(nfkc.length());
        int[] map = new int[nfkc.length()];
        int count = 0;
        char last = 0;
        for (int i = 0; i < nfkc.length(); i++) {
            char c = nfkc.charAt(i);
            if (!isContent(c)) continue;
            if (c == last) continue; // 压缩连续重复
            map[count] = i;
            sb.append(c);
            last = c;
            count++;
        }
        int[] idx = new int[count];
        System.arraycopy(map, 0, idx, 0, count);
        return new Normalized(sb.toString(), idx);
    }

    private static boolean isContent(char c) {
        if (c >= 0x4E00 && c <= 0x9FFF) return true;   // 汉字
        if (c >= 0x3400 && c <= 0x4DBF) return true;   // 扩展 A
        return Character.isLetterOrDigit(c) && c < 0x2000;
    }

    /** 纯 ASCII 的短词要求出现位置不粘连其他字母，避免 "sex" 命中 "sussex"。 */
    private static boolean contains(String haystack, String needle) {
        if (needle.isEmpty()) return false;
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) return false;
            boolean ascii = needle.chars().allMatch(c -> c < 128);
            if (!ascii) return true;
            boolean leftOk = idx == 0 || !isAsciiLetter(haystack.charAt(idx - 1));
            int end = idx + needle.length();
            boolean rightOk = end >= haystack.length() || !isAsciiLetter(haystack.charAt(end));
            if (leftOk && rightOk) return true;
            from = idx + 1;
        }
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /* ---------- 加载 ---------- */

    private static List<Entry> load() {
        InputStream in = SensitiveLexicon.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException("敏感词库缺失：" + RESOURCE);
        }
        List<Entry> list = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String row = line.strip();
                if (row.isEmpty() || row.startsWith("#")) continue;
                String[] parts = row.split("\\|", 4);
                if (parts.length < 4) continue;
                String word = parts[3].strip();
                if (word.isEmpty()) continue;
                list.add(new Entry(
                        Scope.valueOf(parts[0].strip().toUpperCase(Locale.ROOT)),
                        parts[1].strip(),
                        Action.valueOf(parts[2].strip().toUpperCase(Locale.ROOT)),
                        word,
                        normalize(word).value()));
            }
        } catch (Exception e) {
            throw new IllegalStateException("敏感词库解析失败：" + RESOURCE, e);
        }
        if (list.isEmpty()) {
            throw new IllegalStateException("敏感词库为空：" + RESOURCE);
        }
        return list;
    }
}
