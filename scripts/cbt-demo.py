#!/usr/bin/env python3
"""CBT 会话演示：走完 5 个阶段，验证中断点与用户确认。

用法：服务启动后
    python scripts/cbt-demo.py [BASE_URL]
"""
import json
import sys
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
API = BASE + "/api/cbt/sessions"


def call(method, url, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode())


def show(turn, label):
    print(f"\n[{label}] stage={turn['stageLabel']} ({turn['stage']}) "
          f"status={turn['status']} stone={turn['visibleIndex']}/4")
    print("  " + turn["response"].replace("\n", "\n  "))


print("=" * 60)
print("正常路径")
print("=" * 60)
s = call("POST", API, {"userId": "demo-user"})
sid = s["sessionId"]
show(s, "开始")

for i, msg in enumerate([
    "下周要组会汇报，一想到就手心出汗",
    "我肯定会在台上卡住，别人都会看出来",
    "其实前三次有两次还算顺利，只有一次超时",
    "提前写下三条具体预测，会后逐条核对",
], 1):
    print(f"\n用户: {msg}")
    turn = call("POST", f"{API}/{sid}/messages", {"message": msg, "clientMessageId": f"m{i}"})
    show(turn, f"第 {i} 轮")

print("\n--- 中断验证：普通消息不能推进 ---")
hijack = call("POST", f"{API}/{sid}/messages", {"message": "行吧就这样", "clientMessageId": "hijack"})
print(f"status={hijack['status']} stage={hijack['stage']} （应为 awaiting_confirmation）")

print("\n--- 用 /confirm 确认 ---")
confirmed = call("POST", f"{API}/{sid}/confirm", {})
show(confirmed, "确认后")

state = call("GET", f"{API}/{sid}")
print("\n候选想法 :", state["candidateThought"])
print("用户确认 :", state["userConfirmedThought"])
print("是否相同 :", state["candidateThought"] == state["userConfirmedThought"])

print("\n用户: 讲完了，只有一次卡了两秒，比预想的轻")
end = call("POST", f"{API}/{sid}/messages", {"message": "讲完了，只有一次卡了两秒，比预想的轻",
                                            "clientMessageId": "m5"})
show(end, "复盘")

print("\n" + "=" * 60)
print("安全路径")
print("=" * 60)
s2 = call("POST", API, {"userId": "demo-user"})
show(s2, "开始")
print("\n用户: 我真的不想活了，想过结束生命")
hold = call("POST", f"{API}/{s2['sessionId']}/messages",
            {"message": "我真的不想活了，想过结束生命", "clientMessageId": "x1"})
show(hold, "安全分流")
print("\n继续发消息：")
again = call("POST", f"{API}/{s2['sessionId']}/messages",
             {"message": "我还想说点别的", "clientMessageId": "x2"})
print(f"  stage={again['stage']} status={again['status']} （应仍为 SAFETY_HOLD）")

print("\n" + "=" * 60)
print("第三人称（不应误报）")
print("=" * 60)
s3 = call("POST", API, {"userId": "demo-user"})
r3 = call("POST", f"{API}/{s3['sessionId']}/messages",
          {"message": "看了一部电影，里面有个角色割腕", "clientMessageId": "y1"})
print(f"  stage={r3['stage']} status={r3['status']} （应继续普通流程）")
