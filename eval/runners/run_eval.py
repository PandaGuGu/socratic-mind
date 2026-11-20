#!/usr/bin/env python3
"""知识库离线评估：检索指标 + 拒答/引用指标。

只调用本机已有的 HTTP 接口，不直连模型服务：
- --mode search  只打 /api/knowledge/search，算 Recall@K / MRR，零生成成本；
- --mode chat    打 /api/knowledge/chat，算拒答准确率、关键词命中、引用有效率；
- --mode both    两者都跑。

服务端用 stub 档时同样零成本；用真实模型时请先确认服务端配置，避免误花额度。
结果写到 eval/results/（已在 .gitignore），文件名带日期、commit 与 suite。

用法：
    python eval/runners/run_eval.py --suite knowledge --mode both
    python eval/runners/run_eval.py --dataset eval/datasets/knowledge/dev-v1.jsonl \
        --base-url http://localhost:8099 --top-k 5 --mode search
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATASET = ROOT / "eval" / "datasets" / "knowledge" / "dev-v1.jsonl"
RESULT_DIR = ROOT / "eval" / "results"


def post_json(base_url: str, path: str, payload: dict, timeout: float) -> dict:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=body,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        raise SystemExit(f"HTTP {e.code} {path}：{detail}") from e
    except urllib.error.URLError as e:
        raise SystemExit(
            f"连不上 {base_url}（{e.reason}）。先启动：java -jar psych-app/target/psych-app-0.2.0.jar "
            f"--server.port=8099 --spring.profiles.active=h2"
        ) from e


def load_cases(path: Path) -> list[dict]:
    cases = []
    for lineno, raw in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        line = raw.strip()
        if not line:
            continue
        try:
            case = json.loads(line)
        except json.JSONDecodeError as e:
            raise SystemExit(f"{path}:{lineno} 不是合法 JSON：{e}") from e
        if not case.get("id") or not case.get("question"):
            raise SystemExit(f"{path}:{lineno} 缺少 id 或 question")
        if not case.get("expected_refusal") and not case.get("expected"):
            raise SystemExit(f"{path}:{lineno} 既没有 expected 也没有 expected_refusal")
        cases.append(case)
    return cases


def norm(text: str | None) -> str:
    return re.sub(r"\s+", "", text or "")


def hit_matches(hit: dict, expected: list[dict]) -> bool:
    doc = norm(hit.get("documentTitle"))
    section = norm(hit.get("section"))
    for exp in expected:
        edoc = norm(exp.get("document"))
        if not edoc or not (edoc == doc or edoc in doc or doc in edoc):
            continue
        for want in exp.get("sections") or []:
            w = norm(want)
            if not w:
                continue
            if w == section or w in section or (section and section in w):
                return True
    return False


def run_search(base_url: str, cases: list[dict], top_k: int, timeout: float) -> dict:
    per_case = []
    rr_sum = 0.0
    recalled = 0
    answerable = [c for c in cases if not c.get("expected_refusal")]

    for case in answerable:
        result = post_json(base_url, "/api/knowledge/search",
                           {"query": case["question"], "topK": top_k}, timeout)
        hits = result.get("hits") or []
        rank = next((i for i, h in enumerate(hits, 1) if hit_matches(h, case["expected"])), None)
        if rank:
            recalled += 1
            rr_sum += 1.0 / rank
        per_case.append({
            "id": case["id"],
            "rank": rank,
            "top": [
                {"title": h.get("documentTitle"), "section": h.get("section"), "via": h.get("via")}
                for h in hits[:3]
            ],
        })

    n = len(answerable)
    return {
        "cases": n,
        "k": top_k,
        "recall_at_k": round(recalled / n, 3) if n else 0.0,
        "mrr": round(rr_sum / n, 3) if n else 0.0,
        "misses": [c["id"] for c in per_case if not c["rank"]],
        "per_case": per_case,
    }


def run_chat(base_url: str, cases: list[dict], timeout: float) -> dict:
    per_case = []
    refusal_total = refusal_ok = 0
    answer_total = answered = 0
    contain_total = contains_ok = 0
    citation_valid = coverage_sum = 0.0
    model_ids: set[str] = set()

    for case in cases:
        result = post_json(base_url, "/api/knowledge/chat", {"message": case["question"]}, timeout)
        status = result.get("status")
        answer = result.get("answer") or ""
        verification = result.get("verification") or {}
        citations = result.get("citations") or []
        model_ids.add(str(result.get("generatedBy")))

        if case.get("expected_refusal"):
            refusal_total += 1
            ok = status == "insufficient_evidence"
            refusal_ok += int(ok)
        else:
            answer_total += 1
            answered_ok = status == "answered"
            answered += int(answered_ok)
            terms = case.get("must_contain") or []
            ok = answered_ok and all(t in answer for t in terms)
            if terms:
                contain_total += 1
                contains_ok += int(ok)
            if answered_ok:
                valid = bool(verification.get("allCitationsValid")) and bool(citations)
                citation_valid += int(valid)
                coverage_sum += float(verification.get("citationCoverage") or 0.0)

        per_case.append({
            "id": case["id"],
            "status": status,
            "ok": ok,
            "answer": answer[:120],
            "invalidCitations": verification.get("invalidCitationNums"),
            "unsupportedClaims": (verification.get("unsupportedClaims") or [])[:3],
        })

    def rate(num: float, den: int) -> float:
        return round(num / den, 3) if den else 0.0

    return {
        "cases": len(cases),
        "refusal_accuracy": rate(refusal_ok, refusal_total),
        "refusal_cases": refusal_total,
        "answer_rate": rate(answered, answer_total),
        "answer_cases": answer_total,
        "must_contain_rate": rate(contains_ok, contain_total),
        "must_contain_cases": contain_total,
        "citation_valid_rate": rate(citation_valid, answered),
        "avg_citation_coverage": rate(coverage_sum, answered),
        "model_ids": sorted(model_ids),
        "failures": [c for c in per_case if not c["ok"]],
        "per_case": per_case,
    }


def git_commit() -> str:
    try:
        out = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT,
                             capture_output=True, text=True, timeout=5)
        return out.stdout.strip() or "unknown"
    except Exception:
        return "unknown"


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    parser = argparse.ArgumentParser(description="知识库离线评估")
    parser.add_argument("--suite", default="knowledge", choices=["knowledge"])
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--base-url", default="http://localhost:8099")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--mode", default="both", choices=["search", "chat", "both"])
    parser.add_argument("--timeout", type=float, default=90.0)
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args()

    cases = load_cases(args.dataset)
    report = {
        "suite": args.suite,
        "dataset": str(args.dataset.relative_to(ROOT)) if args.dataset.is_relative_to(ROOT) else str(args.dataset),
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "gitCommit": git_commit(),
        "config": {"baseUrl": args.base_url, "topK": args.top_k, "mode": args.mode},
        "cases": len(cases),
    }
    if args.mode in ("search", "both"):
        report["search"] = run_search(args.base_url, cases, args.top_k, args.timeout)
    if args.mode in ("chat", "both"):
        report["chat"] = run_chat(args.base_url, cases, args.timeout)

    RESULT_DIR.mkdir(parents=True, exist_ok=True)
    output = args.output or RESULT_DIR / (
        f"{datetime.now():%Y%m%d-%H%M}-{report['gitCommit']}-{args.suite}-{args.mode}.json")
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"知识评估 · {report['dataset']} · {args.base_url} · {report['cases']} 条")
    if "search" in report:
        s = report["search"]
        print(f"检索 Recall@{s['k']} = {s['recall_at_k']}  MRR = {s['mrr']}"
              f"  （未命中：{', '.join(s['misses']) or '无'}）")
    if "chat" in report:
        c = report["chat"]
        kw = (f"关键词命中 = {c['must_contain_rate']}（{c['must_contain_cases']} 题）"
              if c["must_contain_cases"] else "关键词未设")
        print(f"问答 拒答准确率 = {c['refusal_accuracy']} ({c['refusal_cases']} 题) "
              f"回答率 = {c['answer_rate']} {kw}")
        print(f"引用 有效率 = {c['citation_valid_rate']}  平均覆盖率 = {c['avg_citation_coverage']}"
              f"  生成方 = {', '.join(c['model_ids'])}")
        if c["failures"]:
            print("未通过：" + ", ".join(f["id"] for f in c["failures"]))
        if any(m.startswith("stub") for m in c["model_ids"]):
            print("注意：当前是桩生成器，指标只证明链路与口径，不代表语义质量。")
    print(f"结果：{output.relative_to(ROOT) if output.is_relative_to(ROOT) else output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
