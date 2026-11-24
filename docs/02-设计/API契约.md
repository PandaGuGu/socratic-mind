---
title: API 契约
status: 草案
updated: 2026-11-24
---

# API 契约

> 上位文档：[第 8 章 接口设计](../01-方案/08-接口设计.md)。本文补字段校验、错误码与状态枚举。

## 1. 通用约定

- Base path：`/api`；版本通过 header `X-Api-Version: 1`，不改路径。
- 所有响应含 `requestId`，用于审计（[§1.4](../01-方案/01-产品边界与总体架构.md) 的应用层职责）。
- **身份**：除 `POST /api/cbt/sessions` 外，所有 `/api/cbt/**` 请求必须带 `X-User-Token`。
  首次创建会话的响应头会回发该令牌；带令牌再开会沿用同一身份。令牌由服务端 HMAC 签发，
  客户端无法伪造（见 [ADR-0007](../03-adr/ADR-0007-会话归属与内部接口鉴权.md)）。
  令牌有两版：`v1` 是设备令牌（未登录），`v2` 是账号令牌（登录后）。两者都用同一个请求头，
  区别在可见范围——`v2` 能多看"已认领到账号"的会话，见 [ADR-0009](../03-adr/ADR-0009-账号作为叠加层.md)。
  `v2` 还签了 `token_epoch`：改口令 / 退出其他设备 / 重置口令会让它失效，旧令牌下一次请求 401。
- 内部接口与知识库写入需 `X-Internal-Token`，与用户令牌互不通用。
- 时间一律 RFC3339，UTC。
- 未知字段忽略，不报错；缺必填字段返回 422。

## 2. 错误码

| code | HTTP | 含义 | 前端处理 |
|-|-|-|-|
| `UNAUTHORIZED` | 401 | 缺少或无效 `X-User-Token`（设备令牌与账号令牌都算） | 重新开始一轮，静默换取新令牌 |
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

## 3. 账号接口（v0.5.0）

账号不是使用前提：不登录时接口行为与 v0.4.0 完全一致。接口都在 `/api/auth` 下，
除 `/me` 与 `/claim` 外都返回 `X-User-Token` 响应头（同时也在响应体的 `token` 字段里）。

v0.7.0 的两处变化：
1. 注册/登录**不再自动认领**，只返回 `pendingClaims`；用户点确认后调 `POST /api/auth/claim`；
2. 账号令牌里签着 `token_epoch`；改口令、退出其他设备、重置口令会让旧令牌立即 401。

### POST /api/auth/register

```json
// request
{ "email": "you@example.com", "password": "至少 8 位", "displayName": "可不填，≤32 字" }
```

```json
// response 200
{ "token": "v2....", "account": { "id": "acc_...", "email": "you@example.com",
  "displayName": "小周", "createdAt": "...", "lastLoginAt": "..." },
  "pendingClaims": 3, "recoveryCode": "ABCD-EFGH-JKLM" }
```

- 邮箱统一转小写后存储，唯一索引兜底；重复注册返回 422，口令短于 8 位或邮箱格式不对同样是 422。
- `pendingClaims` 是这台设备上**可以**认领的匿名会话条数；不会自动认领。
- `recoveryCode` 只在注册/重新生成时返回一次；服务端只存 PBKDF2 摘要。
- 限流：20 次/小时/IP。

### POST /api/auth/login

请求 `{ "email": "...", "password": "..." }`，响应同上。邮箱不存在与口令错误都返回同一条 422
（"邮箱或口令不对"），不区分是哪一种。限流：15 次/分钟/(IP+邮箱)。

### POST /api/auth/claim（v0.7.0）

请求空对象。返回 `{"claimed": 3}`。需要账号令牌；未登录 401。
只认领 `account_id IS NULL` 且属于当前设备 id 的会话。

### POST /api/auth/password（v0.7.0）

```json
{ "currentPassword": "旧口令", "newPassword": "至少 8 位" }
```

返回与登录相同的 `AuthResponse`，**当前设备会拿到一枚新令牌**；旧令牌（含其他设备）立即 401。
限流 10 次/10 分钟/IP。

### POST /api/auth/logout-all（v0.7.0）

请求空对象，返回同样的 `AuthResponse`。只推进令牌代数，再给当前设备签新令牌；
其他设备的旧令牌下一次请求 401。

### POST /api/auth/recover（v0.7.0）

```json
{ "email": "you@example.com", "recoveryCode": "ABCD-EFGH-JKLM", "newPassword": "新口令" }
```

用注册时的一次性恢复码重置口令。成功返回 `AuthResponse`（直接登录），所有旧令牌失效；
同一枚恢复码不能再用。错误统一回"邮箱或恢复码不对"，不区分账号是否存在。
限流：5 次/10 分钟/(IP+邮箱)。

### POST /api/auth/recovery-code（v0.7.0）

需要账号令牌，返回 `{"recoveryCode": "..."}`，旧码立即作废。只显示这一次。

### GET /api/auth/me

```json
// 已登录
{ "signedIn": true, "account": { "id": "acc_...", "email": "...", "displayName": "..." } }
// 未登录、令牌无效或没带令牌
{ "signedIn": false }
```

`200 + signedIn:false` 是刻意的：房间页与账号页一打开就问一次，不该为此在控制台刷 401。

### POST /api/auth/delete（v0.6.0）

```json
// request
{ "password": "当前口令" }
// response 200
{ "deleted": true, "releasedSessions": 3 }
```

- 要口令、要前端二次确认；限流 10 次/10 分钟/IP。
- 先解除认领（会话 `account_id` 置空），再删账号；会话不跟着删——第一归属仍是设备。
- **注销即吊销**：产品接口在验签后还会确认账号存在，所以同一枚 `v2` 令牌立刻变 401。
- 口令错返回 422，账号不变；未登录返回 401。

**没有 `POST /logout`。** 令牌是无状态签名串，服务端没有可删的会话记录；写一个"返回 200 但什么也不做"的
退出接口等于骗人。退出登录由客户端丢掉令牌完成（账号页会把登录前的设备令牌换回来）。
注销是另一件事：它删账号，于是令牌真的失效了。

## 4. 关键契约

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

### GET /api/cbt/sessions/page（v0.6.0）

```
GET /api/cbt/sessions/page?limit=40&cursor=<上一页的 nextCursor>
```

```json
{ "items": [ { "sessionId": "cbt_...", "stage": "PROBLEM_IDENTIFICATION", "...": "同 SessionView" } ],
  "nextCursor": "MTc5NTQ5...", "hasMore": true }
```

- 按创建时间倒序；`limit` 1–50，默认 20。
- 游标是 `(createdAt, id)` 的 base64，不是 offset：一边翻一边新开一轮时，offset 会重复或跳过。
  客户端不需要解析，原样带回即可；无效游标返回 422，不会静默回到第一页。
- 可见范围与 `GET /api/cbt/sessions` 完全一致。

### GET /api/cbt/mood/trend（v0.6.0）

```json
{ "days": 30, "from": "2026-10-26", "to": "2026-11-24", "points": 6, "sessions": 3,
  "daily": [ { "date": "2026-11-20", "average": 4.5, "min": 2, "max": 8, "count": 2 } ] }
```

- 只统计当前身份（设备或账号）**看得到**的会话里的自评；别人设备上的分不会混进来。
- 按服务器本地时区分天；`average` 保留一位小数；没有点的日子不返回。
- 只是把已有的点排在一起：不插值、不预测、不产出"改善/恶化"的判断。

### 内部接口（v0.6.0 新增，均需 `X-Internal-Token`）

| 方法 | 路径 | 说明 |
|-|-|-|
| GET | `/api/internal/lexicon` | 词库全表、版本、文件路径、历史快照 |
| PUT | `/api/internal/lexicon` | 整表保存；结构门槛不通过返回 422，旧表继续生效 |
| POST | `/api/internal/lexicon/validate` | 只校验草稿，不落盘 |
| POST | `/api/internal/lexicon/test` | `{text, side: in/out}` → 命中与是否 block |
| POST | `/api/internal/lexicon/rollback` | `{version}`：把该版快照当成一次新保存 |
| POST | `/api/internal/lexicon/reset` | 恢复随包基线，记为新版本 |
| GET | `/api/internal/accounts` | 只读账号视图：会话数、最近一轮、需关注轮次 |

## 5. SSE 事件

| event | data |
|-|-|
| `token` | `{"text": "增量"}` |
| `state` | `{"stage": "...", "status": "...", "pendingQuestion": "..."}` |
| `citation` | `{"evidenceId": "...", "page": 42, "section": "3.2"}` |
| `done` | `{"messageId": "...", "usage": {...}}` |
| `error` | `{"code": "...", "message": "..."}` |

约束：`state` 事件只在后端节点写入后发出，**前端不得回写**（[§8.3](../01-方案/08-接口设计.md)）。

### 分享链接（v0.7.0）

| 方法 | 路径 | 说明 |
|-|-|-|
| POST | `/api/cbt/sessions/{id}/share` | 需要本人令牌；body `{ttlHours}`（1–168，默认 24）；返回 `{path, expiresAt}` |
| DELETE | `/api/cbt/sessions/{id}/shares` | 撤回该会话尚未打开的链接，返回 `{revoked}` |
| GET | `/api/share/{token}` | **公开**，不需要令牌；只能成功一次，之后 410 |

`GET /api/share/{token}` 只返回记录本身（阶段、想法链、情绪、偏差、消息），
**不含联系人电话、内部轨迹、可枚举的会话 id**。

### 本地语音转写（v0.7.0）

| 方法 | 路径 | 说明 |
|-|-|-|
| GET | `/api/asr/status` | `{"available": true/false}` |
| POST | `/api/asr/transcribe` | `Content-Type: audio/wav`，16k 单声道最佳；返回 `{"text": "..."}` |

只在浏览器没有 Web Speech（Firefox）时使用；音频不落库，转写进程结束即删临时文件。
未配置转写命令时回 503，页面提示直接打字。

### 内部接口（v0.7.0 新增）

| 方法 | 路径 | 说明 |
|-|-|-|
| GET | `/api/internal/ai` | 模型延迟 P50/P95、估算 token、失败数、最近调用 |
| GET | `/api/internal/reviews` | 安全规则与词库的当前版本、审核状态 |
| PUT | `/api/internal/reviews` | 登记审核结论；版本号由服务端取当前生效版本 |

## 6. 待补

- [x] 会话列表分页：`/api/cbt/sessions/page` 游标分页（v0.6.0）
- [ ] 消息历史分页（`/history`）——单轮几十条量级，暂未做
- [ ] 文档上传的分片与格式白名单
- [x] 鉴权方案：匿名身份令牌 + 内部令牌（ADR-0007，v0.1.1）
- [x] 账号：注册/登录/me，令牌 v2（ADR-0009，v0.5.0）
- [x] 账号注销与"注销即吊销"（v0.6.0）
- [x] 找回口令（一次性恢复码）、改口令、退出其他设备（v0.7.0）
- [ ] 邮箱验证：没有 SMTP 通道；邮箱目前只是登录名
- [ ] 主动吊销某一台设备（当前只能"退出其他设备"或注销）
