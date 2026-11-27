#!/usr/bin/env python3
"""扫描疑似密钥。只报告位置与规则名，绝不在终端回显密钥内容。

用法：
    python scripts/check-secrets.py            # 扫描工作区已跟踪 + 未忽略的文件
    python scripts/check-secrets.py --staged   # 只扫描暂存区（pre-commit 用）

退出码：0 = 干净；1 = 发现疑似密钥。
规则依据：docs/05-规范/03-外部依赖约定.md
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

SKIP_DIRS = {".git", "target", "node_modules", "dist", ".idea", ".vscode", "__pycache__", ".venv"}
SKIP_FILES = {
    "scripts/check-secrets.py",
    "scripts/pre-commit",
    "docs/05-规范/03-外部依赖约定.md",
}
BINARY_EXT = {
    ".png", ".jpg", ".jpeg", ".gif", ".ico", ".webp", ".pdf", ".zip", ".gz", ".jar",
    ".class", ".woff", ".woff2", ".ttf", ".mp4", ".mp3",
}

RULES: list[tuple[str, re.Pattern[str]]] = [
    ("模型密钥（sk- 前缀）", re.compile(r"\bsk-[A-Za-z0-9_\-]{20,}")),
    ("GitHub 令牌", re.compile(r"\b(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})")),
    ("AWS Access Key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("Google API Key", re.compile(r"\bAIza[0-9A-Za-z_\-]{30,}")),
    ("阿里云 AccessKey", re.compile(r"\bLTAI[0-9A-Za-z]{12,}")),
    ("Slack Token", re.compile(r"\bxox[baprs]-[A-Za-z0-9\-]{10,}")),
    ("私钥块", re.compile(r"-----BEGIN (?:RSA|OPENSSH|EC|DSA|PGP) PRIVATE KEY-----")),
    ("疑似硬编码凭证",
     re.compile(r"(?i)\b(api[_-]?key|secret|token|password|passwd)\b\s*[:=]\s*['\"][A-Za-z0-9/+=_\-]{24,}['\"]")),
]


def repo_root() -> Path:
    try:
        out = subprocess.run(["git", "rev-parse", "--show-toplevel"],
                             capture_output=True, text=True, check=True)
        return Path(out.stdout.strip())
    except Exception:
        return Path.cwd()


def candidate_files(root: Path, staged: bool) -> list[Path]:
    if staged:
        out = subprocess.run(["git", "diff", "--cached", "--name-only", "--diff-filter=ACM"],
                             cwd=root, capture_output=True, text=True, check=False)
        names = [n for n in out.stdout.splitlines() if n.strip()]
    else:
        out = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                             cwd=root, capture_output=True, text=True, check=False)
        names = [n for n in out.stdout.split("\0") if n.strip()]
        if not names:
            names = [str(p.relative_to(root)) for p in root.rglob("*") if p.is_file()]

    files: list[Path] = []
    for name in names:
        rel = name.strip().replace("\\", "/")
        if rel in SKIP_FILES:
            continue
        if any(part in SKIP_DIRS for part in rel.split("/")):
            continue
        p = root / rel
        if not p.is_file() or p.suffix.lower() in BINARY_EXT:
            continue
        files.append(p)
    return files


def mask(secret: str) -> str:
    return secret[:3] + "*" * min(8, max(3, len(secret) - 3))


def scan(path: Path, root: Path) -> list[str]:
    problems: list[str] = []
    try:
        text = path.read_text(encoding="utf-8", errors="ignore")
    except Exception:
        return problems

    for lineno, line in enumerate(text.splitlines(), 1):
        if len(line) > 2000:
            continue
        for name, pattern in RULES:
            m = pattern.search(line)
            if m:
                problems.append(
                    f"{path.relative_to(root).as_posix()}:{lineno}  [{name}]  {mask(m.group(0))}"
                )
    return problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--staged", action="store_true", help="只扫描暂存区")
    args = ap.parse_args()

    root = repo_root()
    files = candidate_files(root, args.staged)
    hits: list[str] = []
    for f in files:
        hits.extend(scan(f, root))

    if hits:
        print(f"发现 {len(hits)} 处疑似密钥（内容已隐藏）：\n")
        for h in hits:
            print("  ✗ " + h)
        print("\n处理建议：")
        print("  1. 从文件中删除，改用环境变量注入；")
        print("  2. 若已提交，轮换该密钥——历史里删掉不算安全；")
        print("  3. 确认是误报时，把该文件加入脚本顶部的 SKIP_FILES 并说明理由。")
        return 1

    print(f"OK：扫描 {len(files)} 个文件，未发现明文密钥。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
