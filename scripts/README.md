# scripts

一次性脚本与运维小工具。**不放业务逻辑**，所有会被应用调用的代码进 `backend/`。

| 脚本 | 用途 | 何时写 |
|-|-|-|
| `../start.cmd` | Windows 双击入口：`start.cmd [up\|ai\|edge\|down\|status\|logs\|psql\|reset]`，只做参数翻译，逻辑在 `stack.ps1` | 已有 |
| `../stop.cmd` | Windows 双击停止：默认保留数据卷，关闭容器与 Docker 的 WSL 虚拟机（释放 vmmem 内存）；`stop.cmd purge` 才删 | 已有 |
| `stack.sh` | 一键起停全部容器：应用 + PostgreSQL，可选 Ollama 向量 | 已有 |
| `stack.ps1` | 同上，Windows 原生终端（参数 `-Ai` / `-Yes`） | 已有 |
| `e2e.sh` | 知识链路验收：健康检查 → 问答 → 拒答 → 检索 → 草稿闸门 → 审核后放行 | 已有 |
| `cbt-demo.py` | CBT 会话演示：五阶段 → 中断 → 确认 → 复盘，含安全分流与第三人称不误报 | 已有 |
| `check-links.py` | 校验 docs 内相对链接 | 已有 |
| `check-secrets.py` | 扫描明文密钥（只报位置与规则名，不回显内容） | 已有 |
| `pre-commit` | 提交前自动跑密钥扫描 | 已有 |
| `ingest.py` / `ingest.sh` | 批量导入真实语料 | 待写（M2 之后） |
| `export-eval.py` | 评估结果汇总成 markdown | M6 |

## 启用 pre-commit 钩子（推荐）

```bash
cp scripts/pre-commit .git/hooks/pre-commit && chmod +x .git/hooks/pre-commit
```

之后每次 `git commit` 都会先扫描暂存区，发现疑似密钥直接拒绝提交。卸载：`rm .git/hooks/pre-commit`。

手动检查：

```bash
python scripts/check-secrets.py           # 全量
python scripts/check-secrets.py --staged  # 只看暂存区
```

规则依据：[外部依赖与付费资源约定](../docs/04-规范/外部依赖与付费资源约定.md)。

## check-links.py

```bash
python scripts/check-links.py
```

扫描 `docs/` 与根 `README.md` 里的相对链接，报告指向不存在的文件。每次改文档结构后跑一次。
