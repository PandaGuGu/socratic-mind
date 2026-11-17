package com.socraticmind.session;

import java.time.Instant;

public record CbtMessage(String role, CbtStage stage, String content, Instant at) {

    public static CbtMessage assistant(CbtStage stage, String content) {
        return new CbtMessage("assistant", stage, content, Instant.now());
    }

    public static CbtMessage user(CbtStage stage, String content) {
        return new CbtMessage("user", stage, content, Instant.now());
    }
}
