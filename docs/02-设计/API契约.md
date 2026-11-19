---
title: API 契约
status: 草案
updated: 2026-11-17
---

# API 契约

> 上位文档：[第 8 章 接口设计](../01-方案/08-接口设计.md)。本文补字段校验、错误码与状态枚举。

## 1. 通用约定

- Base path：`/api`；版本通过 header `X-Api-Version: 1`，不改路径。
- 所有响应含 `requestId`，用于审计（[§1.4](../01-方案/01-产品边界与总体架构.md) 的应用层职责）。
- **身份**：除 `POST /api/cbt/sessions` 外，所有 `/api/cbt/**` 请求必须带 `X-User-Token`。
  首次创建会话的响应头会回发该令牌；带令牌再开会沿用同一身份。令牌由服务端 HMAC 签发，
  客户端无法伪造（见 [ADR-0007](../03-adr/ADR-0007-会话归属与内部接口鉴权.md)）。
- 内部接口与知识库写入需 `X-Internal-Token`，与用户令牌互不通用。
- 时间一律 RFC3339，UTC。
- 未知字段忽略，不报错；缺必填字段返回 422。

## 2. 错误码

| code | HTTP | 含义 | 前端处理 |
|-|-|-|-|
| `UNAUTHORIZED` | 401 | 缺少或无效 `X-User-Token` | 重新开始一轮，静默换取新令牌 |
| `NOT_FOUND` | 404 | 会话不存在**或不属于当前身份**（刻意不区分） | 回列表 |
| `CONFLICT` | 409 | 状态冲突，例如重复确认 | 刷新状态 |
| `TOO_MANY_REQUESTS` | 429 | 触发限流（消息 60/分钟、开新会话 30/小时/IP） | 提示稍后再试 |
| `INVALID_ARGUMENT` | 422 | 参数缺失或格式错误 | 就地提示 |
| `SESSION_ENDED` | 409 | 会话已结束，拒绝继续写 | 引导新建 |
| `DUPLICATE_MESSAGE` | 200 | `clientMessageId` 重复，返回上次结果（**不算错误**） | 忽略 |
| `GRAPH_STAGE_INVALID` | 409 | 当前阶段不接受该输入 | 提示重试 |
| `EVIDENCE_INSUFFICIENT` | 200 | 知识问答证据不足（业务状态，非异常） | 正常展示"暂无法回答" |
| `SAFETY_HOLD` | 200 | 安全分流触发，普通流程暂停 | 展示转介信息，锁定输入 |
| `MODEL_UNAVAILABLE` | 503 | 上游模型不可用 | 重试 + 降级提示 |

`EVIDENCE_INSUFFICIENT` 与 `SAFETY_HOLD` 用 200，因为它们是**预期内的业务结果**，不是接口失败。用 4xx 会让前端把"系统诚实地拒答"渲染成错误页。

## 3. 关键契约

### POST /api/cbt/sessions/{id}/messages

```json
// request
{
  "sessionId": "session_001",
  "message": "……",
  "clientMessageId": "msg_001"
}
```

校验：`message` 长度 1–2000；`clientMessageId` 必填且会话内唯一；`sessionId` 必须与路径一致。

```json
// response
{
  "requestId": "req_...",
  "sessionId": "session_001",
  "stage": "problem_identification",
  "status": "awaiting_user",
  "response": "……",
  "citations": [],
  "pendingQuestion": "……"
}
```

### stage 枚举（与 [§5.3](../01-方案/05-双Graph设计.md) 节点一致）

`safety_screening` / `problem_identification` / `cognitive_extraction` /
`cognitive_review` / `intervention_decision` / `action_confirm` /
`action_review` / `session_summary` / `ended`

### status 枚举

`running` / `awaiting_user` / `safety_hold` / `ended`

## 4. SSE 事件

| event | data |
|-|-|
| `token` | `{"text": "增量"}` |
| `state` | `{"stage": "...", "status": "...", "pendingQuestion": "..."}` |
| `citation` | `{"evidenceId": "...", "page": 42, "section": "3.2"}` |
| `done` | `{"messageId": "...", "usage": {...}}` |
| `error` | `{"code": "...", "message": "..."}` |

约束：`state` 事件只在后端节点写入后发出，**前端不得回写**（[§8.3](../01-方案/08-接口设计.md)）。

## 5. 待补

- [ ] 分页参数（history 接口）
- [ ] 文档上传的分片与格式白名单
- [x] 鉴权方案：匿名身份令牌 + 内部令牌（ADR-0007，v0.1.1）
