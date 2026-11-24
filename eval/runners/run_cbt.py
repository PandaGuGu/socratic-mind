#!/usr/bin/env python3
"""CBT 流程离线评估：阶段推进、确认中断、敏感词、安全暂停、低自评。

评估的是"流程有没有被模型带跑"，不是回答好不好听：
每一轮都比对 stage / status，而不是比对文本。
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
DEFAULT = ROOT / "eval" / "datasets" / "cbt" / "formal-v1.jsonl"
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


def run_case(base: str, case: dict) -> list[dict]:
    status, started, token = call(base, "/api/cbt/sessions", {})
    if status != 200:
        return [{"step": "start", "ok": False, "why": f"HTTP {status}"}]
    session = started["sessionId"]
    results = []

    for index, step in enumerate(case.get("steps") or [], 1):
        action = step.get("action", "message")
        expected_http = step.get("expectHttp", 200)

        if action == "message":
            text = step.get("text", "")
            if step.get("repeat"):
                text = text * int(step["repeat"])
            status, turn, _ = call(base, f"/api/cbt/sessions/{session}/messages",
                                   {"message": text}, token)
        elif action == "confirm":
            status, turn, _ = call(base, f"/api/cbt/sessions/{session}/confirm",
                                   {"thought": step.get("thought")}, token)
        elif action == "end":
            status, turn, _ = call(base, f"/api/cbt/sessions/{session}/end", {}, token)
        elif action == "mood":
            status, turn, _ = call(base, f"/api/cbt/sessions/{session}/mood",
                                   {"score": step.get("score", 5)}, token)
        else:
            results.append({"step": index, "ok": False, "why": f"未知动作 {action}"})
            continue

        ok = status == expected_http
        why = None if ok else f"HTTP {status}，期望 {expected_http}"
        got = {}
        if status == 200:
            got = {"stage": turn.get("stage"), "status": turn.get("status"),
                   "safety": turn.get("safetyStatus")}
            if step.get("expectStage") and turn.get("stage") != step["expectStage"]:
                ok, why = False, f"stage={turn.get('stage')}，期望 {step['expectStage']}"
            if step.get("expectStatus") and turn.get("status") != step["expectStatus"]:
                ok, why = False, f"status={turn.get('status')}，期望 {step['expectStatus']}"
            if step.get("expectSafetyStatus") and turn.get("safetyStatus") != step["expectSafetyStatus"]:
                ok, why = False, f"safety={turn.get('safetyStatus')}，期望 {step['expectSafetyStatus']}"
            if step.get("expectConfirmed") and turn.get("confirmedThought") != step["expectConfirmed"]:
                ok, why = False, f"confirmed={turn.get('confirmedThought')!r}"
        results.append({"step": index, "action": action, "ok": ok, "why": why, "got": got})

        if not ok:
            break
    return results


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    parser = argparse.ArgumentParser(description="CBT 流程评估")
    parser.add_argument("--dataset", type=Path, default=DEFAULT)
    parser.add_argument("--base-url", default="http://localhost:8099")
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args()

    cases = [json.loads(line) for line in args.dataset.read_text(encoding="utf-8-sig").splitlines()
             if line.strip()]

    per_case = []
    failures = []
    for case in cases:
        results = run_case(args.base_url, case)
        ok = all(r["ok"] for r in results)
        per_case.append({"id": case["id"], "name": case.get("name"), "ok": ok, "steps": results})
        if not ok:
            first = next(r for r in results if not r["ok"])
            failures.append({"id": case["id"], "step": first["step"], "why": first.get("why")})

    report = {
        "suite": "cbt",
        "dataset": str(args.dataset.relative_to(ROOT)),
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "cases": len(cases),
        "passed": len(cases) - len(failures),
        "passRate": round((len(cases) - len(failures)) / len(cases), 3) if cases else 0,
        "failures": failures,
        "perCase": per_case,
    }
    text = json.dumps(report, ensure_ascii=False, indent=2)
    output = args.output or RESULT_DIR / f"{datetime.now():%Y%m%d-%H%M}-cbt.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text, encoding="utf-8")

    print(f"CBT 流程评估 · {len(cases)} 条 · 通过 {report['passed']} · 通过率 {report['passRate']}")
    if failures:
        for f in failures:
            print(f"  未通过 {f['id']} 第 {f['step']} 步：{f['why']}")
    print(f"结果：{output}")
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
