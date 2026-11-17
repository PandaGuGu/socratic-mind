#!/usr/bin/env bash
# 全链路验收脚本：入库 → 检索 → 证据 → 生成 → 引用校验 → 审核放行
#
# 用法：
#   backend 打包后启动服务，然后：
#   bash scripts/e2e.sh [BASE_URL]
#
# 依赖：curl、python（仅用于格式化输出）

set -euo pipefail
BASE="${1:-http://localhost:8080}"
J() { python -c "import sys,json;d=json.load(sys.stdin);print(json.dumps(d,ensure_ascii=False,indent=2))"; }

line() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

line "0. 健康检查"
curl -s "$BASE/actuator/health"; echo

line "1. 知识库现状"
curl -s "$BASE/api/knowledge/stats" | J

line "2. 正常问答（应带引用且校验通过）"
curl -s -X POST "$BASE/api/knowledge/chat" -H 'Content-Type: application/json' \
  -d '{"message":"自动思维和核心信念有什么区别？"}' | J

line "3. 无资料的问题（必须拒答，不能编）"
curl -s -X POST "$BASE/api/knowledge/chat" -H 'Content-Type: application/json' \
  -d '{"message":"自行车链条掉了怎么装回去"}' | J

line "4. 检索调试（看每一路的得分与来源）"
curl -s -X POST "$BASE/api/knowledge/search" -H 'Content-Type: application/json' \
  -d '{"query":"认知检验的步骤","topK":3}' | J

line "5. 上传一份草稿（不应可检索）"
DOC=$(curl -s -X POST "$BASE/api/knowledge/documents" -H 'Content-Type: application/json' -d '{
  "title":"示例：睡眠卫生（草稿）",
  "source":"内部示例",
  "publish":false,
  "content":"# 睡眠卫生\n\n## 作息\n\n固定的起床时间比固定的入睡时间更容易维持生物钟的稳定。"
}' | python -c "import sys,json;print(json.load(sys.stdin)['documentId'])")
echo "documentId=$DOC"

line "5.1 草稿状态下提问（应拒答）"
curl -s -X POST "$BASE/api/knowledge/chat" -H 'Content-Type: application/json' \
  -d '{"message":"固定起床时间对生物钟有什么作用"}' | python -c "
import sys,json;d=json.load(sys.stdin);print('status =',d['status']);print('reason =',[t for t in d['trace'] if t.startswith('evaluate')])"

line "5.2 审核通过后再问（应可答）"
curl -s -X POST "$BASE/api/knowledge/documents/$DOC/review" -H 'Content-Type: application/json' \
  -d '{"decision":"approve"}' | J
curl -s -X POST "$BASE/api/knowledge/chat" -H 'Content-Type: application/json' \
  -d '{"message":"固定起床时间对生物钟有什么作用"}' | python -c "
import sys,json;d=json.load(sys.stdin)
print('status =',d['status'])
print('answer =',d['answer'])
print('cites  =',[c['documentTitle']+' · '+str(c.get('section')) for c in d['citations']])
print('verify =',d['verification'])"

line "完成"
