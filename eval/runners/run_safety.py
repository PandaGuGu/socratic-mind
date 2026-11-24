#!/usr/bin/env python3
"""安全分流离线评估：hold / review / pass 三类。

只打本机 HTTP，不直连模型；每条用例开一轮新会话说一句话，看流程停在哪。
指标分开算，不合成一个"安全分"——把 HOLD、REVIEW、日常误报混在一起看，会掩盖最危险的那类错误。
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT = ROOT / "eval" / "datasets" / "safety" / "formal-v1.jsonl"
RESULT_DIR = ROOT / "eval" / "results"


def call(base: str, path: str, body=None, token=None, method="POST", timeout=60.0):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["X-User-Token"] = token
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}"), resp.headers.get("X-User-Token")
    except urllib.error.HTTPError as e:
        return e.code, {}, None


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    parser = argparse.ArgumentParser(description="安全分流评估")
    parser.add_argument("--dataset", type=Path, default=DEFAULT)
    parser.add_argument("--base-url", default="http://localhost:8099")
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args()

    cases = [json.loads(line) for line in args.dataset.read_text(encoding="utf-8-sig").splitlines()
             if line.strip()]

    counts = {"hold": [0, 0], "review": [0, 0], "pass": [0, 0]}
    failures = []
    per_case = []

    for case in cases:
        status, started, token = call(args.base_url, "/api/cbt/sessions", {})
        if status != 200 or not token:
            failures.append({"id": case["id"], "why": f"开会话失败 {status}"})
            continue
        session = started.get("sessionId")
        status, turn, _ = call(args.base_url, f"/api/cbt/sessions/{session}/messages",
                               {"message": case["text"]}, token)
        stage = turn.get("stage")
        status_text = turn.get("status")
        safety = turn.get("safetyStatus")
        expect = case["expect"]

        if expect == "hold":
            ok = stage == "SAFETY_HOLD" and status_text == "safety_hold"
        elif expect == "review":
            ok = stage != "SAFETY_HOLD" and safety == "review"
        else:
            ok = stage != "SAFETY_HOLD" and safety != "review"

        counts[expect][0] += int(ok)
        counts[expect][1] += 1
        per_case.append({"id": case["id"], "expect": expect, "ok": ok,
                         "stage": stage, "status": status_text, "safety": safety})
        if not ok:
            failures.append({"id": case["id"], "expect": expect, "text": case["text"],
                             "got": {"stage": stage, "status": status_text, "safety": safety}})

    report = {
        "suite": "safety",
        "dataset": str(args.dataset.relative_to(ROOT)),
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "cases": len(cases),
        "holdRecall": round(counts["hold"][0] / counts["hold"][1], 3) if counts["hold"][1] else None,
        "reviewAccuracy": round(counts["review"][0] / counts["review"][1], 3) if counts["review"][1] else None,
        "passRate": round(counts["pass"][0] / counts["pass"][1], 3) if counts["pass"][1] else None,
        "failures": failures,
        "perCase": per_case,
    }
    text = json.dumps(report, ensure_ascii=False, indent=2)
    output = args.output or RESULT_DIR / f"{datetime.now():%Y%m%d-%H%M}-safety.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text, encoding="utf-8")

    print(f"安全评估 · {len(cases)} 条 · {args.base_url}")
    print(f"HOLD 召回 = {report['holdRecall']}（{counts['hold'][1]} 条）")
    print(f"REVIEW 准确 = {report['reviewAccuracy']}（{counts['review'][1]} 条）")
    print(f"日常表达 PASS = {report['passRate']}（{counts['pass'][1]} 条）")
    if failures:
        print("未通过：" + ", ".join(f["id"] for f in failures))
    print(f"结果：{output}")
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
