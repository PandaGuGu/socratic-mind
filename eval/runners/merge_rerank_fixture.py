"""把两位"扮演 cross-encoder"的标注者的独立打分合并成 fixture，并报告重排前后的差异。

完整流程（在仓库根目录执行）：
1. 用一次性 SpringBootTest 导出候选：`backend/psych-app` 里临时加一个测试，
   对固定查询调 `HybridRetriever.retrieve(q, 10)`，输出 `target/rerank-pairs.json`，
   每条带 `key`（检索文本的短 SHA，见 `Hashes.shortSha`）。
   用内容键而不是 chunk id：id 每次重建语料都会变，标注文件第二天就静默失效。
2. 让两个标注者各自按 0–1 给"这段能不能直接回答问题"打分，写成
   `target/rerank-keys-a.tsv` / `-b.tsv`（`查询<TAB>key<TAB>分数`，互不通气）。
3. 跑本脚本 → 写 `backend/psych-infrastructure/src/main/resources/rerank/agent-v1.json`。

一致性指标不是装饰：top-1 一致而 top-3 只有 0.87，说明标注在头部稳定、中段有噪声——
这正是"只用它验证管道、不用它宣称排序质量"的理由。
"""
import json
import statistics
from collections import OrderedDict
from pathlib import Path

TARGET = Path("backend/psych-app/target")
OUT = Path("backend/psych-infrastructure/src/main/resources/rerank/agent-v1.json")


def load_tsv(name):
    scores = {}
    for line in (TARGET / name).read_text(encoding="utf-8-sig").splitlines():
        if not line.strip():
            continue
        query, key, score = line.split("\t")
        scores.setdefault(query.strip(), {})[key.strip()] = float(score)
    return scores


a = load_tsv("rerank-keys-a.tsv")
b = load_tsv("rerank-keys-b.tsv")
pairs = json.loads((TARGET / "rerank-pairs.json").read_text(encoding="utf-8"))

merged = OrderedDict()
stats = []
deltas = []
for block in pairs:
    q = block["query"]
    cands = block["candidates"]
    if set(a[q]) != set(b[q]):
        raise SystemExit(f"两份打分的候选集合不一致：{q}")
    top1_a = max(a[q].items(), key=lambda kv: kv[1])[0]
    top1_b = max(b[q].items(), key=lambda kv: kv[1])[0]
    top3_a = {k for k, _ in sorted(a[q].items(), key=lambda kv: -kv[1])[:3]}
    top3_b = {k for k, _ in sorted(b[q].items(), key=lambda kv: -kv[1])[:3]}
    diffs = [abs(a[q][k] - b[q][k]) for k in a[q]]
    stats.append({"query": q, "top1Agree": top1_a == top1_b,
                  "top3Overlap": len(top3_a & top3_b) / 3.0,
                  "meanAbsDiff": round(statistics.fmean(diffs), 3)})

    average = {k: round((a[q][k] + b[q][k]) / 2, 2) for k in a[q]}
    merged[q] = average

    original = [c for c in cands if c["rank"] == 1][0]
    reranked = max(cands, key=lambda c: average[c["key"]])
    if original["key"] != reranked["key"]:
        deltas.append((q, original["text"][:38], reranked["text"][:38],
                       original["rank"], average[reranked["key"]]))

fixture = OrderedDict([
    ("model", "agent-subagent-v1"),
    ("note", "子代理扮演 cross-encoder 的独立打分，两次取均值；不是 bge-reranker 输出。"
             "键是检索文本的短 SHA（内容寻址），重建语料后仍然对得上。接真模型后本文件退化为回归基准。"),
    ("labelers", 2),
    ("agreement", {
        "top1": sum(s["top1Agree"] for s in stats) / len(stats),
        "top3MeanOverlap": round(statistics.fmean(s["top3Overlap"] for s in stats), 3),
        "meanAbsDiff": round(statistics.fmean(s["meanAbsDiff"] for s in stats), 3),
    }),
    ("queries", merged),
])

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

print(f"wrote {OUT}")
print("top1 agree =", fixture["agreement"]["top1"],
      "| top3 overlap =", fixture["agreement"]["top3MeanOverlap"],
      "| mean abs diff =", fixture["agreement"]["meanAbsDiff"])
print(f"重排改变了 top-1 的查询：{len(deltas)} / {len(pairs)}")
for q, before, after, orig_rank, score in deltas:
    print(f"  · {q}\n      原 top1: {before}…")
    print(f"      新 top1: {after}…（原本第 {orig_rank} 名，重排分 {score}）")
