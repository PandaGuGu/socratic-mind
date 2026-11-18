---
title: frontend 说明
status: 草案
---

# frontend

Vue 3。M5 起步（[第 10 章](../docs/01-方案/10-里程碑与开发顺序.md)）。

## 先看这两个

| 文件 | 是什么 | 什么时候用 |
|-|-|-|
| `preview/world-demo.html` | 房间（默认界面）→ 说话 → 纸片飞出窗 → 点窗看窗外泡泡 | **默认界面的设计基线**；动效与叙事看这个 |
| `preview/ui-demo.html` | 三栏文档式界面（知识问答 / CBT / 记录） | 列表模式与长答案排版；也是"不接受隐喻"时的降级方案 |

试的顺序：点墙角的小人 → 选一句话发出去（纸片会飞向窗外，窗外多一颗星，地上石子前进）→ 点窗户（进入窗外，泡泡是历次对话）→ 点任一泡泡（纸片浮起，可读当时的对话）。

两者共用 [UI 设计规范](../docs/04-规范/UI设计规范.md) 的 token，不另起一套。

## 已经跑起来的那一版

`preview/world-demo.html` 的逻辑已被搬进后端并接上真实接口：
`backend/psych-app/src/main/resources/static/room/index.html`，访问 `http://localhost:8099/room`。
原型保留作设计基线，不要再往里加交互；要改交互去改 static 里那份。

设计意图与数据映射见 [世界观与导航](../docs/02-设计/世界观与导航.md)。

## 三个页面

| 页面 | 对接 | 关键点 |
|-|-|-|
| 知识学习 | `POST /api/knowledge/chat` | 引用要能点开定位到页码，不是装饰 |
| CBT 对话 | `POST /api/cbt/sessions/{id}/messages` | SSE 流式；`stage` 只读展示 |
| 会话记录 | `GET /api/cbt/sessions/{id}/history` | 展示"用户确认过什么"，而不是全部消息 |

## 三条前端纪律

1. **不写 State。** 只有"发消息"能推进流程；不得直接改 `stage` 或任何 Graph 字段（[§8.4](../docs/01-方案/08-接口设计.md)）。
2. **`stage` 按枚举渲染，不做字符串匹配。** 新增节点时前端应"不认识就降级展示"，而不是崩。
3. **不隐藏"我不知道"。** 证据不足是正常业务结果（`EVIDENCE_INSUFFICIENT`），不要渲染成错误页。

## 目录约定（M5 定稿）

```
src/
├── api/          # 只放请求封装，不放业务判断
├── views/        # 三个页面
├── components/
├── stores/       # 会话状态镜像（只读）
└── types/        # 与 API 契约对齐的 d.ts
```
