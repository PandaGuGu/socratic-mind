package com.socraticmind.app.web;

import com.socraticmind.app.asr.AsrService;
import com.socraticmind.app.web.support.RateLimiter;
import com.socraticmind.app.web.support.TooManyRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * 本地语音转写接口：只给"浏览器没有 Web Speech"的情况用。
 *
 * 音频不落库、不进日志；临时文件转完即删。
 * 没有配置转写命令时回 503，页面据此提示"直接打字"，而不是静默失败。
 */
@RestController
@RequestMapping("/api/asr")
public class AsrController {

    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private final AsrService asr;
    private final RateLimiter limiter = new RateLimiter();

    public AsrController(AsrService asr) {
        this.asr = asr;
    }

    public record Status(boolean available) {
    }

    public record Transcript(String text) {
    }

    @GetMapping("/status")
    public Status status() {
        return new Status(asr.available());
    }

    @PostMapping(value = "/transcribe", consumes = "audio/wav")
    public ResponseEntity<Object> transcribe(@RequestBody byte[] audio, HttpServletRequest http) {
        if (!asr.available()) {
            return ResponseEntity.status(503)
                    .body(Map.of("code", "ASR_UNAVAILABLE",
                            "message", "这个环境没有配置本地语音转写，请直接用文字输入"));
        }
        if (audio == null || audio.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("code", "EMPTY_AUDIO", "message", "没有收到音频"));
        }
        if (audio.length > MAX_BYTES) {
            return ResponseEntity.status(413).body(Map.of("code", "AUDIO_TOO_LARGE", "message", "录音太长了"));
        }
        if (!limiter.allow("asr:" + ip(http), 20, Duration.ofMinutes(1))) {
            throw new TooManyRequestsException("转写太频繁，请稍后再试");
        }
        try {
            return ResponseEntity.ok(new Transcript(asr.transcribe(audio)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_AUDIO", "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).body(Map.of("code", "ASR_FAILED", "message", e.getMessage()));
        }
    }

    private static String ip(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
