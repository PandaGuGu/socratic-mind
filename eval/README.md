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

## 跑法（M6 定稿）

```bash
# 预期形态，脚本尚未实现
python runners/run_eval.py --suite knowledge --dataset datasets/knowledge/v1.jsonl
python runners/run_eval.py --suite safety    --dataset datasets/safety/v1.jsonl
```

## 三组对照

A 通用模型直答 / B 通用 RAG / C 本方案，定义与指标见 [§2.8](../docs/01-方案/02-相关方案横向对比.md)。这三组数据同时是"是否继续自研"的决策依据（[§2.3.1](../docs/01-方案/02-相关方案横向对比.md)），不只是验收材料。

## 纪律

- 用例只追加，不改已有 `id` 的语义。
- 每条用例留 `note` 说明动机。
- 自动化分数与人工评审意见分开存放，不混成一个数。
