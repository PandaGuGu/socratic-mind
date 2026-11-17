---
title: ADR-0005 两个 Graph 独立编译、不共享业务 State
status: 已接受
date: 2026-11-17
---

# ADR-0005：两个 Graph 独立编译、不共享业务 State

- **状态**：已接受
- **日期**：2026-11-17
- **相关**：[§5.1](../01-方案/05-双Graph设计.md)、[§5.4](../01-方案/05-双Graph设计.md)、[第 8 章](../01-方案/08-接口设计.md)

## 背景

KnowledgeGraph 与 CbtGraph 都要检索知识，直觉上"一个 Graph + 一个状态类"更省事：用一个 `ChatState` 存所有字段，靠 `currentStage` 区分。

## 决策

两个 `CompiledGraph` 独立编译，**共享 `RagService`，不共享 State**。`threadId` 命名空间隔离，会话表分开。

## 备选方案与被否原因

| 方案 | 为什么没选 |
|-|-|
| 单 Graph + 单 State | 知识问答的情绪/事件字段会污染 CBT 会话；`currentStage` 承担两套语义，条件边变得不可读 |
| 单 Graph + 子图 | 子图适合"同一流程复用片段"，但不解决状态污染；且两套流程的入口、中断点、持久化策略都不同 |
| 共享 State 但只读隔离（约定） | 靠约定维持的隔离，在加第三个场景时必然破 |

## 后果

**好处：** State 类各自最小；CBT 的 `userConfirmedThought` 不会被知识问答写入；任一 Graph 可独立替换（例如换成 Python LangGraph 而不动另一个）。

**代价：** 共享逻辑（检索、引用组装）必须收敛到 `RagService`，否则会重复实现；两个 Graph 的提示词、路由要分两套维护。

**复查触发条件：** 若出现第三个场景（如"医生辅助"）且其 80% 节点与现有 Graph 重合，则改为子图复用，但仍不共享 State 类。

## 备注

约束落地为一条代码规则：`psych-rag` 的公开签名里不得出现 `KnowledgeState` / `CbtState`，它只认查询对象与证据列表。
