package com.socraticmind.session;

import java.time.Instant;

/**
 * 一次情绪自评。
 *
 * 边界写死在这里：score 只能来自**用户自己点的那个数字**，
 * 不允许由模型或规则推断后写入（EmotionTagger 只做词标签，不产出这个）。
 * turn 是记录时的轮次，用来把曲线和对话对齐；同分重复提交保留多条，
 * 因为"同一轮里点了两次"本身也是可解释的事实。
 */
public record MoodPoint(int turn, int score, Instant at) {

    public static final int MIN = 0;
    public static final int MAX = 10;

    public MoodPoint {
        if (score < MIN || score > MAX) {
            throw new IllegalArgumentException("情绪自评必须在 0–10 之间");
        }
        if (at == null) at = Instant.now();
    }

    public static MoodPoint of(int turn, int score) {
        return new MoodPoint(turn, score, Instant.now());
    }
}
