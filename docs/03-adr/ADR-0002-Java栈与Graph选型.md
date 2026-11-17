---
title: ADR-0002 Java 栈与 Graph 选型
status: 已接受
date: 2026-11-17
---

# ADR-0002：Java + Spring AI + Spring AI Alibaba Graph

- **状态**：已接受
- **日期**：2026-11-17
- **相关**：[第 1 章](../01-方案/01-产品边界与总体架构.md)、[第 9 章](../01-方案/09-技术选型与依赖.md)、[§2.3](../01-方案/02-相关方案横向对比.md)

## 背景

需要同时满足三件事：Graph 级的状态机与中断恢复、Java 生态的企业落地（私有化、鉴权、审计）、RAG 组件可替换。Python 侧有 LangGraph，Java 侧有 Spring AI Alibaba Graph。

## 决策

后端 Java（Spring Boot + Spring AI），工作流用 **Spring AI Alibaba Graph**，RAG 用 Spring AI 的 `VectorStore` 打底、自研 `RagService` 兜住证据结构与引用校验。

## 备选方案与被否原因

| 方案 | 为什么没选 |
|-|-|
| Python + LangGraph | 编排能力最强，但企业私有化交付、鉴权审计、团队栈不匹配；且本方案需要一个"能和 Java 业务系统共库共事务"的后端 |
| Dify / FastGPT | 无节点级 checkpoint，会话=消息历史，无法表达"回到第 4 步只改一个字段" |
| 自研状态机（不用 Graph 框架） | 中断、恢复、状态历史都要自己写，等于重造 Graph 核心 |
| LangGraph4j | 同源思路，但社区与文档弱于 Alibaba 版本；若后续需要可替换（RAG 已解耦，见 ADR-0005） |

## 后果

**好处：** Graph 与业务同进程同事务；中断恢复由框架提供；全栈 Apache-2.0 系，可私有化。

**代价：** Spring AI 与 Spring AI Alibaba 的版本节奏不一致，**版本组合必须锁定并核实**，这是本项目最大的工程风险；中文社区资料少于 Python 侧。

**复查触发条件：** 团队转 Python 栈；或 Spring AI Alibaba Graph 的持久化能力无法满足业务表一致性要求。

## 备注

**待核实**：①Checkpointer 对 PostgreSQL 的官方支持情况；②当前发布版本与 Spring AI 的兼容矩阵。核实方法：读 spring-ai-alibaba 仓库的版本说明与 `examples/documentation` 的 `pom.xml`，不自行组合版本。

**2026-11-17 补充（M1–M2 实现时的偏离，需记录在案）：**

M1–M2 落地时**没有引入 Spring AI 与 Spring AI Alibaba Graph**，而是定义了三个端口
（`ChatPort` / `EmbeddingPort` / `KnowledgeStore`）并给出了桩实现与 HTTP 实现。

理由：
1. 验收点不同。M1–M2 要证明的是"证据能不能用、引用对不对得上"，与用哪个模型 SDK 无关；
2. 版本风险前置。在拿到真实 key 之前引入一个无法联调的 SDK，只会把不确定性堆到后面；
3. 隔离成本为零。`RagService` 与流程层只认端口，接 Spring AI 时是新增一个 `SpringAiChatAdapter`，
   不动任何业务代码——这正是本 ADR 当初选择"RAG 与 Graph 解耦"的收益。

**这不构成本 ADR 的推翻。** 它仍是目标架构；偏离只发生在时间顺序上。触发回补的条件：
M3 接入真实模型时，若需要结构化输出、重试、多模型路由等能力，就切到 Spring AI 的
`ChatClient`，而不是继续手写 HTTP。

Graph 部分同理：M1–M3 的流程是一条直线，用朴素编排；M4 需要中断与 Checkpoint 时再引入框架。
