#!/usr/bin/env python3
"""校验 docs/ 与根 README 里的相对链接是否指向真实文件。

用法：
    python scripts/check-links.py
    python scripts/check-links.py --root . --quiet

退出码：0 = 全部可解析，1 = 存在坏链。
不校验外链（http/https），也不校验锚点是否正确——只解决"文件被改名/移走"这类最常见的失效。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

LINK_RE = re.compile(r"\[(?P<text>[^\]]*)\]\((?P<target>[^)\s]+)(?:\s+\"[^\"]*\")?\)")
FENCE_RE = re.compile(r"^(```|~~~).*?^\1[^\n]*$", re.MULTILINE | re.DOTALL)
INLINE_CODE_RE = re.compile(r"`[^`\n]*`")


def strip_code(text: str) -> str:
    """去掉围栏代码块与行内代码，避免把文档里的示例链接当成真链接。"""
    text = FENCE_RE.sub("", text)
    return INLINE_CODE_RE.sub("``", text)


def iter_markdown(root: Path) -> list[Path]:
    files = list(root.glob("docs/**/*.md"))
    for name in ("README.md", "CONTRIBUTING.md"):
        p = root / name
        if p.exists():
            files.append(p)
    return sorted(set(files))


def check(root: Path, quiet: bool) -> int:
    problems: list[str] = []
    checked = 0

    for md in iter_markdown(root):
        text = strip_code(md.read_text(encoding="utf-8"))
        for m in LINK_RE.finditer(text):
            target = m.group("target").strip()
            if target.startswith(("http://", "https://", "mailto:", "#")):
                continue
            path_part = target.split("#", 1)[0]
            if not path_part:
                continue
            checked += 1
            resolved = (md.parent / path_part).resolve()
            if not resolved.exists():
                problems.append(
                    f"{md.relative_to(root).as_posix()}"
                    f"  ->  {target}"
                    f"   （解析为 {resolved}，不存在）"
                )

    if problems:
        print(f"发现 {len(problems)} 个坏链（共检查 {checked} 个相对链接）：\n")
        for p in problems:
            print("  ✗ " + p)
        return 1

    if not quiet:
        print(f"OK：{checked} 个相对链接全部可解析。")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".", help="仓库根目录")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()
    return check(Path(args.root).resolve(), args.quiet)


if __name__ == "__main__":
    sys.exit(main())
