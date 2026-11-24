package com.socraticmind.app.asr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本地语音转写：给没有 Web Speech 的浏览器（Firefox）一条退路。
 *
 * 它不把音频发给云端：命令是本机进程（当前实现是 Windows 自带的 System.Speech 识别器），
 * 写临时文件、转完即删。没有配置命令时明确报"未配置"，而不是假装成功。
 *
 * 为什么用外部进程而不是 Java 库：离线构建不允许再引一个大依赖；
 * 系统语音包已经在本机，直接用它最省事。要换 Vosk/Whisper，只改这一条命令。
 */
@Service
public class AsrService {

    private static final Logger log = LoggerFactory.getLogger(AsrService.class);

    private final String command;
    private final long timeoutSeconds;

    public AsrService(@Value("${psych.asr.command:}") String command,
                      @Value("${psych.asr.timeout-seconds:30}") long timeoutSeconds) {
        this.command = command == null ? "" : command.strip();
        this.timeoutSeconds = Math.max(5, timeoutSeconds);
    }

    public boolean available() {
        return !command.isEmpty();
    }

    /**
     * 转写一段 WAV（16k、单声道、16 bit 最稳）。空文本不是异常：可能只是没说话。
     */
    public String transcribe(byte[] wav) {
        if (!available()) {
            throw new IllegalStateException("这个环境没有配置本地语音转写服务");
        }
        if (wav == null || wav.length == 0) {
            throw new IllegalArgumentException("音频是空的");
        }
        Path file = null;
        Path result = null;
        try {
            file = Files.createTempFile("socratic-asr-", ".wav");
            Files.write(file, wav);
            result = Files.createTempFile("socratic-asr-", ".txt");
            String cmdText = command.replace("{file}", file.toAbsolutePath().toString())
                    .replace("{result}", result.toAbsolutePath().toString());
            List<String> cmd = split(cmdText);
            Process process = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("语音转写超时");
            }
            String out = read(process.getInputStream());
            String err = read(process.getErrorStream());
            if (process.exitValue() != 0) {
                log.warn("语音转写进程失败：{}", err.strip());
                throw new IllegalStateException("语音转写失败");
            }
            // 优先读结果文件：命令行工具经管道输出中文常被系统代码页改坏。
            String text = command.contains("{result}")
                    ? new String(Files.readAllBytes(result), StandardCharsets.UTF_8)
                    : out;
            text = text.replace("\uFEFF", "").strip();
            return text.length() > 2000 ? text.substring(0, 2000) : text;
        } catch (IOException e) {
            throw new IllegalStateException("语音转写进程起不来：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("语音转写被中断", e);
        } finally {
            for (Path temp : new Path[]{file, result}) {
                if (temp == null) continue;
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件删不掉不影响本次结果
                }
            }
        }
    }

    /** 命令里可能有引号包住的路径；只按空白切分，保留引号内的空格。 */
    static List<String> split(String command) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : command.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (!current.isEmpty()) {
                    out.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) out.add(current.toString());
        return out;
    }

    private static String read(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
}
