#!/usr/bin/env python3
"""把目录里的 Markdown 资料批量入库（默认只提交审核，不自动发布）。

用法：
    python eval/runners/ingest_corpus.py --dir eval/datasets/private/corpus
    python eval/runners/ingest_corpus.py --dir ... --approve   # 演示用：跳过人工审核

说明：
- 只调用本机 /api/knowledge，不连接模型，不产生计费调用；
- 需要内部令牌：--token 或环境变量 PSYCH_INTERNAL_TOKEN，开发档默认 dev-internal-token；
- 已存在同名文档会跳过，避免重复入库（重新入库请先在页面归档或换标题）。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def request(base: str, path: str, token: str, payload: dict | None = None) -> dict | list:
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        base.rstrip("/") + path,
        data=body,
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "Accept": "application/json",
            "X-Internal-Token": token,
        },
        method="GET" if body is None else "POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        raise SystemExit(f"HTTP {e.code} {path}：{detail[:200]}") from e
    except urllib.error.URLError as e:
        raise SystemExit(f"连不上 {base}（{e.reason}）。先启动服务，再加 --spring.profiles.active=h2") from e


def meta_of(text: str, fallback: str) -> tuple[str, str, str]:
    title = next((ln[2:].strip() for ln in text.splitlines() if ln.startswith("# ")), fallback)
    source = re.search(r"^\s*-\s*来源[：:]\s*(.+)$", text, re.M)
    url = re.search(r"^\s*-\s*原文[：:]\s*(\S+)$", text, re.M)
    return title, (source.group(1).strip() if source else "未标注来源"), (url.group(1).strip() if url else "")


def main() -> int:
    parser = argparse.ArgumentParser(description="批量入库 Markdown 资料")
    parser.add_argument("--dir", type=Path, default=ROOT / "eval/datasets/private/corpus")
    parser.add_argument("--base-url", default="http://localhost:8099")
    parser.add_argument("--token", default=os.environ.get("PSYCH_INTERNAL_TOKEN", "dev-internal-token"))
    parser.add_argument("--collection", default="who-zh")
    parser.add_argument("--approve", action="store_true", help="入库后立即审核通过（演示用）")
    args = parser.parse_args()

    files = sorted(args.dir.glob("*.md")) + sorted(args.dir.glob("*.txt"))
    if not files:
        raise SystemExit(f"{args.dir} 里没有 .md / .txt")

    existing = {d.get("title") for d in request(args.base_url, "/api/knowledge/documents", args.token)}
    for path in files:
        text = path.read_text(encoding="utf-8")
        title, source, url = meta_of(text, path.stem)
        if title in existing:
            print(f"跳过（已存在）：{title}")
            continue
        result = request(args.base_url, "/api/knowledge/documents", args.token, {
            "title": title,
            "source": source,
            "sourceUrl": url,
            "collection": args.collection,
            "content": text,
            "publish": True,
        })
        doc_id = result.get("documentId")
        print(f"入库 {title} → {result.get('status')} · 子块 {result.get('childChunks')} · {doc_id}")
        if args.approve:
            reviewed = request(args.base_url, f"/api/knowledge/documents/{doc_id}/review", args.token,
                               {"decision": "approve"})
            print(f"  审核 → {reviewed.get('status')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
