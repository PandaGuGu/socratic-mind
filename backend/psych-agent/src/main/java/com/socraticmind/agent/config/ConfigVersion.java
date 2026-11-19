package com.socraticmind.agent.config;

/** 历史版本的一条摘要：编号、时间、谁改的。内容要单独取（可能很大）。 */
public record ConfigVersion(int version, String updatedAt, String updatedBy) {
}
