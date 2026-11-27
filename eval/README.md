---
title: eval 说明
status: 定稿
updated: 2026-11-27
---

# eval

离线评估。测试集要早于功能开始攒——否则到最后只剩两周造数据。

指标口径、建集规则与已跑结果见 [评估与测试集](../docs/06-评估/02-评估与测试集.md)；
这里只说怎么跑。

## 结构

```
eval/
├── datasets/
│   ├── knowledge/    # 知识问答：问题 + 标准出处
│   ├── cbt/          # CBT 流程：四条路径
│   └── safety/       # 安全：含误报场景
├── runners/          # 跑分与入库脚本
└── results/          # 输出，不入库（见 .gitignore）
```

## 跑法

脚本只打本机 HTTP 接口，不直连模型；stub 档零成本。

```bash
# 知识问答：search 只算 Recall@K / MRR，chat 另算拒答、关键词、引用
python eval/runners/run_eval.py --suite knowledge --mode both --base-url http://localhost:8099

# 安全与流程（确定性，stub 档即可）
python eval/runners/run_safety.py --base-url http://localhost:8099
python eval/runners/run_cbt.py    --base-url http://localhost:8099

# 三组对照（A 直答 / B 通用 RAG / C 本方案）
python eval/runners/run_compare.py --limit 10 --model qwen3-8b
```

- `--mode search`：Recall@K、MRR，不触发生成；
- `--mode chat`：拒答准确率、关键词命中、引用有效率；
- 真实模型下 chat 很慢（CPU 单轮 30–60 秒），正式集一轮 15–20 分钟。

## 测试集

| 文件 | 条数 | 说明 |
|-|-|-|
| `datasets/knowledge/formal-v1.jsonl` | 34 | 26 可答 + 8 应拒答，基于仓库内示例资料 |
| `datasets/safety/formal-v1.jsonl` | 25 | 12 HOLD + 5 REVIEW + 8 日常表达 |
| `datasets/cbt/formal-v1.jsonl` | 8 | 五阶段、确认中断、改说法、敏感词、安全暂停、低自评、超长、提前结束 |
| `datasets/knowledge/who-v1.jsonl` | 20 | WHO 中文语料，需本地私有语料 |

`safety` 与 `cbt` 在 stub 档就能跑：它们验证的是确定性流程。

## 语料入库

```bash
# 默认只提交审核（status=reviewing），审核通过才可检索
python eval/runners/ingest_corpus.py --dir eval/datasets/private/corpus

# 复现基线时跳过人工审核
python eval/runners/ingest_corpus.py --dir eval/datasets/private/corpus --approve
```

真实语料放 `eval/datasets/private/`（已 gitignore）。本机验证用过 WHO 中文实况报道
（CC BY-NC-SA 3.0 IGO），只证明管道与切分，不作为临床依据。
`--approve` 只为复现基线；正常流程应在 `/internal/knowledge.html` 里逐篇审核。

## 纪律

- 用例只追加，不改已有 `id` 的语义；
- 每条留 `note` 说明动机；
- 自动化分数与人工评审意见分开存放，不混成一个数；
- 结果文件按 `YYYYMMDD-<commit>-<suite>.json` 命名，便于回溯。
