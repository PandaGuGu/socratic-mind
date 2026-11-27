# scripts

一次性脚本与运维小工具。**不放业务逻辑**，会被应用长期调用的代码进 `backend/`。

| 脚本 | 用途 |
|-|-|
| `stack.sh` / `stack.ps1` | 一键起停全部容器（应用 + PostgreSQL，可选 Ollama / Caddy） |
| `../start.cmd` / `../stop.cmd` | Windows 双击入口，只做参数翻译，逻辑在上面的脚本里 |
| `local-app.ps1` | 本机跑 jar：起 H2 档并打开页面，不碰 Docker |
| `e2e.sh` | 知识链路验收：健康检查 → 问答 → 拒答 → 检索 → 草稿闸门 → 审核后放行 |
| `cbt-demo.py` | CBT 会话演示：五阶段 → 中断 → 确认 → 复盘，含安全分流与第三人称不误报 |
| `loadtest.py` | 并发压测（会话域 / 检索），输出 P50/P95/P99 与吞吐 |
| `asr_transcribe.ps1` | 本机语音转写（Windows System.Speech，zh-CN），由 `/api/asr` 调用 |
| `asr_say.ps1` | 合成一段中文语音，用来验证转写管道 |
| `check-links.py` | 校验 `docs/` 与根 README 里的相对链接 |
| `check-secrets.py` | 扫描明文密钥，只报位置与规则名，不回显内容 |
| `pre-commit` | 提交前自动跑密钥扫描 |

## pre-commit 钩子（推荐）

```bash
cp scripts/pre-commit .git/hooks/pre-commit && chmod +x .git/hooks/pre-commit
```

之后每次 `git commit` 都会先扫描暂存区，发现疑似密钥直接拒绝提交。卸载：`rm .git/hooks/pre-commit`。

```bash
python scripts/check-secrets.py           # 全量
python scripts/check-secrets.py --staged  # 只看暂存区
```

规则依据：[外部依赖约定](../docs/05-规范/03-外部依赖约定.md)。

## check-links.py

```bash
python scripts/check-links.py
```

扫描 `docs/` 与根 `README.md` 中的相对链接，报告指向不存在的文件。每次改文档结构后跑一次。
