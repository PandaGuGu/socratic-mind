#!/usr/bin/env python3
"""并发压测：只打本机 HTTP，不直连数据库。

用法：
    python scripts/loadtest.py --requests 400 --concurrency 8
    python scripts/loadtest.py --requests 1200 --concurrency 32 --port 8099

它回答的问题很具体：在固定并发下，接口的 P50/P95/P99 是多少、错误率多少、
以及"会话写入"这条最重的路（整轮覆盖写）在连接池与 PG 上撑不撑得住。

刻意不压模型：真实模型一次几秒，压出来的数字衡量的是 Ollama，不是这套代码。
模型延迟看 /internal/ai。
"""

from __future__ import annotations

import argparse
import json
import random
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field

MESSAGES = [
    "下周组会要汇报，一想到就手心出汗",
    "我肯定会搞砸",
    "前三次有两次其实还行，组长只提了数据口径",
    "提前写下三条具体预测，会后逐条核对",
    "汇报前把三条预测写在便签上",
    "写了，汇报比想象中顺，大家照常问口径",
]


@dataclass
class Sample:
    action: str
    ms: float
    status: int
    ok: bool


@dataclass
class Result:
    samples: list[Sample] = field(default_factory=list)
    lock: threading.Lock = field(default_factory=threading.Lock)

    def add(self, sample: Sample) -> None:
        with self.lock:
            self.samples.append(sample)


def request(base: str, path: str, method: str = "GET", token: str | None = None,
            body: dict | None = None, timeout: float = 20.0,
            internal: str | None = None) -> tuple[int, dict, str | None]:
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["X-User-Token"] = token
    if internal:
        headers["X-Internal-Token"] = internal
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, json.loads(raw or "{}"), resp.headers.get("X-User-Token")
    except urllib.error.HTTPError as e:
        return e.code, {}, None
    except Exception:
        return 0, {}, None


def worker(base: str, total: int, result: Result, seed: int, internal: str | None) -> None:
    rnd = random.Random(seed)
    status, started, token = request(base, "/api/cbt/sessions", "POST", body={})
    if status != 200 or not token:
        result.add(Sample("start", 0, status, False))
        return
    session = started.get("sessionId")
    step = 0

    for _ in range(total):
        actions = ["message", "read", "mood", "page", "share"]
        weights = [45, 20, 12, 18, 5]
        if internal:
            actions.append("knowledge")
            weights.append(20)
        action = rnd.choices(actions, weights=weights)[0]
        t0 = time.perf_counter()
        if action == "message":
            text = MESSAGES[step % len(MESSAGES)]
            step += 1
            code, data, _ = request(base, f"/api/cbt/sessions/{session}/messages", "POST",
                                    token, {"message": text})
            ok = code == 200
            if ok and data.get("status") in ("ended", "safety_hold"):
                code2, started2, token2 = request(base, "/api/cbt/sessions", "POST", token, {})
                if code2 == 200 and token2:
                    token, session, step = token2, started2.get("sessionId"), 0
        elif action == "read":
            code, _, _ = request(base, f"/api/cbt/sessions/{session}", token=token)
            ok = code == 200
        elif action == "mood":
            code, _, _ = request(base, f"/api/cbt/sessions/{session}/mood", "POST",
                                 token, {"score": rnd.randint(0, 10)})
            ok = code == 200
        elif action == "page":
            code, _, _ = request(base, "/api/cbt/sessions/page?limit=10", token=token)
            ok = code == 200
        elif action == "share":
            code, data, _ = request(base, f"/api/cbt/sessions/{session}/share", "POST",
                                    token, {"ttlHours": 1})
            ok = code == 200
        else:
            code, _, _ = request(base, "/api/knowledge/search", "POST", None,
                                 {"query": "认知检验一般分几步", "topK": 5}, internal=internal)
            ok = code == 200
        result.add(Sample(action, (time.perf_counter() - t0) * 1000, code, ok))


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round(p * len(ordered))) - 1))
    return ordered[index]


def main() -> int:
    parser = argparse.ArgumentParser(description="并发压测（会话域）")
    parser.add_argument("--base-url", default="http://localhost:8099")
    parser.add_argument("--requests", type=int, default=400, help="每个并发工人的请求数")
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--output", default=None)
    parser.add_argument("--internal-token", default=None,
                        help="给了就把知识检索也压进去（pgvector 路径）")
    args = parser.parse_args()

    result = Result()
    threads = [
        threading.Thread(target=worker,
                         args=(args.base_url, args.requests, result, i, args.internal_token),
                         daemon=True)
        for i in range(args.concurrency)
    ]
    started = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.perf_counter() - started

    samples = list(result.samples)
    ok = [s for s in samples if s.ok]
    rate_limited = [s for s in samples if s.status == 429]
    # 429 是限流生效，不是服务错误；单独计数，避免把"该拦的拦住了"算成故障。
    errors = len(samples) - len(ok) - len(rate_limited)
    latencies = [s.ms for s in ok]
    report = {
        "baseUrl": args.base_url,
        "concurrency": args.concurrency,
        "requestsPerWorker": args.requests,
        "total": len(samples),
        "ok": len(ok),
        "errors": errors,
        "rateLimited": len(rate_limited),
        "errorRate": round(errors / len(samples), 4) if samples else 0,
        "elapsedSec": round(elapsed, 2),
        "throughput": round(len(samples) / elapsed, 1) if elapsed else 0,
        "p50ms": round(percentile(latencies, 0.50), 1),
        "p95ms": round(percentile(latencies, 0.95), 1),
        "p99ms": round(percentile(latencies, 0.99), 1),
        "avgMs": round(statistics.mean(latencies), 1) if latencies else 0,
        "byAction": {},
        "statusCounts": {},
    }
    for action in sorted({s.action for s in samples}):
        subset = [s for s in ok if s.action == action]
        report["byAction"][action] = {
            "count": len([s for s in samples if s.action == action]),
            "p50ms": round(percentile([s.ms for s in subset], 0.50), 1),
            "p95ms": round(percentile([s.ms for s in subset], 0.95), 1),
        }
    for s in samples:
        report["statusCounts"][str(s.status)] = report["statusCounts"].get(str(s.status), 0) + 1

    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(text + "\n")
    return 0 if errors == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
