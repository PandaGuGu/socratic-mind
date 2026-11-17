# scripts

一次性脚本与运维小工具。**不放业务逻辑**，所有会被应用调用的代码进 `backend/`。

| 脚本 | 用途 | 何时写 |
|-|-|-|
| `e2e.sh` | 知识链路验收：健康检查 → 问答 → 拒答 → 检索 → 草稿闸门 → 审核后放行 | 已有 |
| `cbt-demo.py` | CBT 会话演示：五阶段 → 中断 → 确认 → 复盘，含安全分流与第三人称不误报 | 已有 |
| `check-links.py` | 校验 docs 内相对链接 | 已有 |
| `ingest.py` / `ingest.sh` | 批量导入真实语料 | 待写（M2 之后） |
| `export-eval.py` | 评估结果汇总成 markdown | M6 |

## check-links.py

```bash
python scripts/check-links.py
```

扫描 `docs/` 与根 `README.md` 里的相对链接，报告指向不存在的文件。每次改文档结构后跑一次。
