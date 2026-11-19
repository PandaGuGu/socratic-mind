package com.socraticmind.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 配置文件的历史快照。
 *
 * 为什么需要它：策略、流程、安全规则都能在后台改，但改之前是什么样、谁改的、
 * 能不能退回去——v0.1.1 之前只有"版本号 +1"。一个改坏的正则，没有回滚就只能去翻 git，
 * 而线上值班的人通常既没有 git 权限，也没有那个时间。
 *
 * 存法：{@code <historyRoot>/<kind>/v<n>.json}，只追加、不覆盖。
 * 回滚不是"恢复旧文件"，而是"把旧版本当成一次新的保存"——这样每一次生效都有快照可查，
 * 不会出现"回滚之后 history 与当前不一致"的糊涂账。
 */
public final class ConfigHistory {

    private static final Pattern VERSION_FILE = Pattern.compile("v(\\d+)\\.json");

    private ConfigHistory() {
    }

    public static void snapshot(Path historyRoot, String kind, int version, Object value, ObjectMapper mapper) {
        try {
            Path dir = historyRoot.resolve(kind);
            Files.createDirectories(dir);
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(dir.resolve("v" + version + ".json").toFile(), value);
        } catch (IOException e) {
            throw new UncheckedIOException("写入配置历史失败：" + kind + " v" + version, e);
        }
    }

    public static List<Integer> versions(Path historyRoot, String kind) {
        Path dir = historyRoot.resolve(kind);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> VERSION_FILE.matcher(f.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(m -> Integer.parseInt(m.group(1)))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读取配置历史失败：" + kind, e);
        }
    }

    public static <T> T read(Path historyRoot, String kind, int version, Class<T> type, ObjectMapper mapper) {
        Path file = historyRoot.resolve(kind).resolve("v" + version + ".json");
        if (!Files.isRegularFile(file)) return null;
        try {
            return mapper.readValue(file.toFile(), type);
        } catch (IOException e) {
            throw new UncheckedIOException("读取配置快照失败：" + kind + " v" + version, e);
        }
    }
}
