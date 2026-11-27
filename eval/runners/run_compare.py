#!/usr/bin/env python3
"""三组对照：A 通用模型直答 / B 通用 RAG / C 本方案。

定义（与 docs/01-概览/03-方案对比.md §7 对齐）：
- A：把问题直接交给通用模型，不检索、不校验；
- B：先检索 top-k，再把片段拼进提示词交给同一个模型，不做引用校验与拒答判定；
- C：本系统的 /api/knowledge/chat：检索 → 证据判定 → 生成 → 引用校验。

为什么必须用同一个模型：换模型就不是"方案差异"，而是"模型差异"。
B 的片段用检索接口返回的 preview（约 120 字），不含完整块；这是它的真实劣势之一，
报告里要写清，不能拿它当"通用 RAG 的上限"。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT = ROOT / "eval" / "datasets" / "knowledge" / "formal-v1.jsonl"
RESULT_DIR = ROOT / "eval" / "results"

REFUSAL_HINTS = ("资料不足", "不足以回答", "没有提到", "未提及", "无法回答", "不知道")


def post(base: str, path: str, body: dict, timeout: float) -> dict:
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(base.rstrip("/") + path, data=data,
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def llm(ollama: str, model: str, system: str, user: str, timeout: float) -> str:
    data = json.dumps({
        "model": model,
        "messages": [{"role": "system", "content": system},
                     {"role": "user", "content": user + "\n/no_think"}],
        "temperature": 0.2,
        "max_tokens": 200,
        "think": False,
    }, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(ollama.rstrip("/") + "/v1/chat/completions", data=data,
                                 headers={"Content-Type": "application/json",
                                          "Authorization": "Bearer ollama"}, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    return (body.get("choices", [{}])[0].get("message", {}).get("content") or "").strip()


def refused(text: str) -> bool:
    return any(h in text for h in REFUSAL_HINTS)


def pick(cases: list[dict], limit: int) -> list[dict]:
    answerable = [c for c in cases if not c.get("expected_refusal")]
    refusal = [c for c in cases if c.get("expected_refusal")]
    half = max(1, limit // 2)
    return answerable[:half] + refusal[:limit - half]


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    parser = argparse.ArgumentParser(description="三组对照")
    parser.add_argument("--dataset", type=Path, default=DEFAULT)
    parser.add_argument("--base-url", default="http://localhost:8099")
    parser.add_argument("--ollama", default="http://localhost:11434")
    parser.add_argument("--model", default="qwen3-8b")
    parser.add_argument("--limit", type=int, default=10)
    parser.add_argument("--timeout", type=float, default=180)
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args()

    cases = [json.loads(line) for line in args.dataset.read_text(encoding="utf-8-sig").splitlines()
             if line.strip()]
    cases = pick(cases, args.limit)

    groups = {"A_direct": [], "B_rag": [], "C_system": []}
    for case in cases:
        question = case["question"]
        want_refusal = bool(case.get("expected_refusal"))
        terms = case.get("must_contain") or []
        row = {"id": case["id"], "expected_refusal": want_refusal}

        t0 = time.perf_counter()
        try:
            a = llm(args.ollama, args.model,
                    "你是一个通用助手。直接回答问题，不要提及检索或资料。", question, args.timeout)
            row["A_direct"] = {"answer": a, "refused": refused(a), "sec": round(time.perf_counter() - t0, 2)}
        except Exception as e:
            row["A_direct"] = {"error": str(e), "sec": round(time.perf_counter() - t0, 2)}

        t0 = time.perf_counter()
        try:
            hits = post(args.base_url, "/api/knowledge/search", {"query": question, "topK": 5},
                        args.timeout).get("hits") or []
            context = "\n\n".join(f"[{i}] {h.get('documentTitle')} / {h.get('section')}\n{h.get('preview')}"
                                  for i, h in enumerate(hits, 1))
            b = llm(args.ollama, args.model,
                    "只根据下面给出的资料回答；资料没有就说“资料不足”。不要编造。\n\n" + context,
                    question, args.timeout)
            row["B_rag"] = {"answer": b, "refused": refused(b), "hits": len(hits),
                            "sec": round(time.perf_counter() - t0, 2)}
        except Exception as e:
            row["B_rag"] = {"error": str(e), "sec": round(time.perf_counter() - t0, 2)}

        t0 = time.perf_counter()
        try:
            c = post(args.base_url, "/api/knowledge/chat", {"message": question}, args.timeout)
            answer = c.get("answer") or ""
            row["C_system"] = {
                "answer": answer,
                "refused": c.get("status") == "insufficient_evidence",
                "citations": len(c.get("citations") or []),
                "citationValid": bool((c.get("verification") or {}).get("allCitationsValid")),
                "sec": round(time.perf_counter() - t0, 2),
            }
        except Exception as e:
            row["C_system"] = {"error": str(e), "sec": round(time.perf_counter() - t0, 2)}

        # 关键词命中只对可答题算
        if not want_refusal and terms:
            for key in groups:
                text = (row.get(key) or {}).get("answer") or ""
                row.setdefault("termsHit", {})[key] = all(t in text for t in terms)
        groups["A_direct"].append(row)
        groups["B_rag"].append(row)
        groups["C_system"].append(row)

    def summary(key: str) -> dict:
        rows = groups[key]
        answerable = [r for r in rows if not r["expected_refusal"]]
        refusal = [r for r in rows if r["expected_refusal"]]
        answered = [r for r in answerable if not (r.get(key) or {}).get("refused")]
        correctly_refused = [r for r in refusal if (r.get(key) or {}).get("refused")]
        terms_rows = [r for r in answerable if r.get("termsHit")]
        terms_ok = [r for r in terms_rows if r["termsHit"].get(key)]
        lat = [(r.get(key) or {}).get("sec", 0) for r in rows if not (r.get(key) or {}).get("error")]
        return {
            "cases": len(rows),
            "answerable": len(answerable),
            "refusal": len(refusal),
            "answerRate": round(len(answered) / len(answerable), 3) if answerable else None,
            "refusalAccuracy": round(len(correctly_refused) / len(refusal), 3) if refusal else None,
            "keywordHitRate": round(len(terms_ok) / len(terms_rows), 3) if terms_rows else None,
            "avgSec": round(sum(lat) / len(lat), 1) if lat else None,
        }

    report = {
        "dataset": str(args.dataset.relative_to(ROOT)),
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "model": args.model,
        "limit": len(cases),
        "summary": {k: summary(k) for k in groups},
        "note": "B 组用检索 preview 拼上下文，不等同于通用 RAG 的上限；指标只作方案间相对比较。",
        "rows": groups["C_system"],
    }
    text = json.dumps(report, ensure_ascii=False, indent=2)
    output = args.output or RESULT_DIR / f"{datetime.now():%Y%m%d-%H%M}-compare-{args.model.replace(':','-')}.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text, encoding="utf-8")

    print(f"三组对照 · {len(cases)} 条 · 模型 {args.model}")
    for key in groups:
        s = report["summary"][key]
        print(f"  {key:10s} 回答率={s['answerRate']} 拒答准确={s['refusalAccuracy']} "
              f"关键词={s['keywordHitRate']} 平均={s['avgSec']}s")
    print(f"结果：{output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
