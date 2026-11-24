---
title: eval 说明
status: 草案
---

# eval

离线评估。M6 起步（[第 10 章](../docs/01-方案/10-里程碑与开发顺序.md)），但**测试集要在 M2 之前就开始攒**——否则 M6 只剩两周时间造数据。

## 结构

```
eval/
├── datasets/
│   ├── knowledge/    # 知识问答：问题 + 标准出处
│   ├── cbt/          # CBT 流程：四条路径
│   └── safety/       # 安全：含误报场景
├── runners/          # 跑分脚本
└── results/          # 输出，不入库（见 .gitignore）
```

用例格式与建集规则见 [docs/05-评估/测试集说明.md](../docs/05-评估/测试集说明.md)。

## 跑法

知识检索/问答脚本已可用（只打本机 HTTP 接口，不直连模型；stub 档零成本）：

```bash
python eval/runners/run_eval.py --suite knowledge --mode both --base-url http://localhost:8099
python eval/runners/run_eval.py --mode search --dataset eval/datasets/knowledge/dev-v1.jsonl
```

- `--mode search`：Recall@K、MRR，不触发生成；
- `--mode chat`：拒答准确率、关键词命中、引用有效率；
- `dev-v1.jsonl` 基于仓库内示例语料，只验证脚本与口径，不是临床评估结果；
- `who-v1.jsonl` 基于 WHO 中文实况报道的 20 条真实语料题（15 可答 + 5 应拒答），用于对比哈希向量与真实 embedding；依赖 `eval/datasets/private/corpus/` 下的本地语料（不入库）。

安全与 CBT 流程也有脚本：

```bash
python eval/runners/run_safety.py --base-url http://localhost:8099
python eval/runners/run_cbt.py --base-url http://localhost:8099
```

三组对照（A 直答 / B 通用 RAG / C 本方案）：

```bash
python eval/runners/run_compare.py --limit 10 --model qwen3-8b
```

正式测试集（v0.5.0）：

| 文件 | 条数 | 说明 |
|-|-|-|
| `datasets/knowledge/formal-v1.jsonl` | 34 | 26 可答 + 8 应拒答，基于仓库内三份示例资料 |
| `datasets/safety/formal-v1.jsonl` | 25 | 12 HOLD + 5 REVIEW + 8 日常表达 |
| `datasets/cbt/formal-v1.jsonl` | 8 | 五阶段、确认中断、改说法、敏感词、安全暂停、低自评、超长、提前结束 |
| `datasets/knowledge/who-v1.jsonl` | 20 | WHO 中文语料，需本地私有语料 |

`safety` 与 `cbt` 在 stub 档就能跑：它们验证的是确定性流程，不需要模型。
`knowledge` 的 chat 模式在真实模型上很慢（CPU 单轮约 30–60 秒），正式集一轮 15–20 分钟。

## 语料入库

```bash
# 默认只提交审核（status=reviewing），审核通过才可检索
python eval/runners/ingest_corpus.py --dir eval/datasets/private/corpus

# 演示用：入库后立即审核通过，跳过人工审核
python eval/runners/ingest_corpus.py --dir eval/datasets/private/corpus --approve
```

真实语料放在 `eval/datasets/private/`（已 gitignore），不进版本库。
本机验证用过 WHO 中文实况报道（CC BY-NC-SA 3.0 IGO），只证明管道与切分，不作为临床依据。

复现 WHO 基线：

```bash
python eval/runners/ingest_corpus.py --dir eval/datasets/private/corpus --approve
python eval/runners/run_eval.py --dataset eval/datasets/knowledge/who-v1.jsonl --mode both
```

`--approve` 只为复现基线跳过人工审核；正常流程应在 `/internal/knowledge.html` 里逐篇审核。

## 正式集结果（2026-11-24，本机 Ollama）

`knowledge/formal-v1.jsonl` 34 条（26 可答 + 8 应拒答），`bge-m3` + `qwen3-8b`：

| 指标 | 结果 |
|-|-|
| 回答率 | 1.0（26/26） |
| 拒答准确率 | 1.0（8/8） |
| 关键词命中 | 1.0（26/26） |
| 引用有效率 | 1.0；平均覆盖率 0.847 |
| 生成延迟 | P50 22.2 s / P95 30.0 s（35 次） |
| 向量延迟 | P50 112 ms / P95 195 ms（44 次） |

`safety/formal-v1.jsonl` 25 条：HOLD 召回 1.0（12/12）、REVIEW 准确 1.0（5/5）、日常 PASS 1.0（8/8）。
`cbt/formal-v1.jsonl` 8 条：8/8。

三组对照（4 条小样本）：A 直答拒答准确率 0.0、关键词 0.5；B 通用 RAG 拒答 1.0、关键词 1.0、平均 6.4 s；
C 本方案拒答 1.0、关键词 1.0、平均 14.0 s。
B 用检索 preview 拼上下文，不是通用 RAG 的上限；样本小，只证明口径可用。

## 真实语料基线（2026-11-20）

`who-v1` 20 条，检索 topK=5：

| 向量 | Recall@5 | MRR | 回答率 | 拒答准确率 |
|-|-|-|-|-|
| stub-hash-256 | 0.933 | 0.622 | 0.8 | 0.0 |
| BGE-M3（Ollama，1024 维） | 1.0 | 0.822 | 1.0 | 0.0 |

BGE-M3 修掉了 3 条口语化误拒，并把 MRR 从 0.622 提到 0.822；5 条无答案题仍被回答。
问题已经从“语义召回”转到“证据是否真的回答了问题”，下一步需要问题类型与答案可验证性判断，而不是继续调相似度阈值。

## 三组对照

A 通用模型直答 / B 通用 RAG / C 本方案，定义与指标见 [§2.8](../docs/01-方案/02-相关方案横向对比.md)。这三组数据同时是"是否继续自研"的决策依据（[§2.3.1](../docs/01-方案/02-相关方案横向对比.md)），不只是验收材料。

## 纪律

- 用例只追加，不改已有 `id` 的语义。
- 每条用例留 `note` 说明动机。
- 自动化分数与人工评审意见分开存放，不混成一个数。
