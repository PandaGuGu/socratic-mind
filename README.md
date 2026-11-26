# Socratic Mind

**一个把 CBT 自助练习做成"确定性骨架 + 受控生成"的开源实现。**
流程由状态机推进，模型只在被允许的位置说话；每条结论都要能指回知识库里的证据。

工程代号 `psych-assistant`　·　Java 21 模块化单体　·　Spring AI Alibaba Graph　·　PostgreSQL/pgvector（开发用 H2）

| 版本 | 测试 | 模型 | 状态 |
|-|-|-|-|
| v0.6.0 | 202 项通过 | 云端 / 本地 Ollama / 桩三档热切换，保存即生效 | 流程画布多库与起点、审核扩面与会话复核、用户数据导出；未上线 |
| v0.5.1 | 194 项通过 | 默认接本地 Ollama（qwen3-8b + bge-m3），离线可切回桩 | 检索补上重排、语料补上生命周期与权限、后台补上真登录；未上线 |
| v0.5.0 | 155 项通过 | 默认接本地 Ollama（qwen3-8b + bge-m3），离线可切回桩 | 一条命令起全部服务；账号可选，未上线 |

![房间：点一下小人，它才开始问](docs/assets/current/room.png)

*产品入口是一间房间，不是聊天列表。点墙角的小人开口，说完的话变成纸片飞出窗外；地上五颗石子表示走到第几步。*

## 它是什么

两个工作流，共用一个审核过的心理知识库：

- **知识学习**：回答心理学与治疗理论问题，每条结论附出处；证据不足时明确拒答。
- **CBT 自助**：围绕一个具体困扰，走完"情境 → 自动思维 → 认知检验 → 微型行动 → 复盘"，其中"确认"必须由用户自己按下。

## 它不是什么

| 不是 | 原因 |
|-|-|
| 不是聊天机器人 | 聊天机器人优化"说得像不像人"；这里优化"这句话有没有出处、用户走到第几步、这句该不该说" |
| 不是诊断工具 | 不推断精神障碍、不给药物或治疗建议。声明疗效即进入医疗器械监管 |
| 不是多 Agent | 两个流程足够；多 Agent 解决的是知识分散问题，这里知识是集中的 |
| 不是低代码平台拼得出来的 | 平台把会话建模成消息历史；这里需要可暂停、可确认、可回滚的业务状态机 |

## 四个设计选择

**1. 流程不交给模型。**
阶段推进由代码与配置决定，模型只在"改写想法""生成总结"两个点被调用。改一条分支不用改代码，改配置即可。

**2. 不做无据生成。**
回答必须先检索、再生成、再校验引用。检索不到就返回"资料不足"，这是正常结果，不是失败。
模型自己说"资料不足"时，状态也会跟着变成拒答——不靠提示词自觉。

**3. 规则、话术、限流都能改，但改不坏。**
后台可以新建和删除安全规则、改系统话术、调限流；保存前会跑固定用例回放，把"我不想活了"改成不再暂停会被拒绝。

**4. 心理记录默认拿不走。**
会话归属用服务端签发的匿名令牌，不属于你的会话一律表现为"不存在"，id 也不再是自增序号。
不注册就能完整使用；注册是自愿的叠加层——账号只扩大"能看哪些记录"，不改写"这一轮属于哪台设备"，
退出登录后本机记录仍在（[ADR-0009](docs/03-adr/ADR-0009-账号作为叠加层.md)）。
注销账号则相反：账号与它签出的令牌立即失效，记录回到这台设备名下，不会跟着账号一起消失。

## 一次对话长什么样

```
情境   最近有什么事一直搁在心里？
想法   那个时刻，你脑子里冒出来的第一句话是什么？
检验   我们把它当成一个预测，而不是事实。有没有出现过不一样的情况？
行动   最小的一步可以是什么？           ← 计划里出现"写/试/做"会追问执行细节
确认   把改写后的说法与计划复述给你，等你确认   ← 中断点，普通消息推不动
复盘   后来实际发生了什么？
总结   存下这一轮
```

任何一步命中安全规则，流程立即停在转介页，给出 **12356** 与 **120**，后续消息不会把对话拉回普通流程。

用户侧还有四件小事，都是为了"把话说出来"和"知道有没有变好"：**语音输入**（Chrome/Edge 浏览器内转写；Firefox 走本机转写，不落库）、**情绪刻度**（自己点一个 0–10，单轮画折线，窗外画 30 天）、**危机卡片**（热线 + 你自己留的联系人 + 一段可以复制/拨号/发短信的话，系统不代发）、**偏差提示**（候选，不是诊断）。

记录可以带走，但只交给一个人：房间里的"窗外"支持生成**一次性链接**，24 小时过期、只能打开一次、本人可撤回；不带联系人电话，打印后不再受系统保护。

## 跑起来

### 方式一：Docker（全部服务，推荐）

Windows 上**双击根目录的 `start.cmd`** 就行：生成 `.env`（随机密钥）→ 构建镜像 → 起 PostgreSQL/pgvector → 等健康检查 → 打开房间。
要停就双击 `stop.cmd`（连 Docker 的 WSL 虚拟机一起关闭，任务管理器里的 vmmem 内存会释放）；其余动作走参数：`start.cmd status`、`start.cmd logs`、`start.cmd edge`。

命令行等价写法：

```bash
bash scripts/stack.sh up        # Git Bash / WSL / Linux / macOS
```

```powershell
powershell -ExecutionPolicy Bypass -File scripts\stack.ps1 up   # Windows 原生终端
```

数据落在命名卷里，`down` 不丢，`reset` 才清空。

启动结束时会打印**后台登录账号**：用户名 `admin`，口令是首次运行生成并写进 `.env` 的 `PSYCH_OPERATOR_PASSWORD`。
它只在第一次启动时写入数据库（摘要落库），之后在后台改口令不会被覆盖；`.env` 里那行可以随时回看。

**一个 compose 就够，除模型层外没有别的容器要起。** `docker-compose.yml` 里只有两个服务，
没有 Redis、没有消息队列——限流走数据库表的原子 upsert，静态前端打在 jar 里由应用自己 serve，
流程是同步状态机、不存在跨进程解耦的点。反向代理做成了可选叠层（`start.cmd edge` 起 Caddy，
负责 TLS、压缩、安全头），本机开发不需要。模型默认是桩（哈希向量 256 维，零成本），所以断网也能完整跑通两个流程。

| 依赖 | 放在哪 | 说明 |
|-|-|-|
| 应用本体 | 容器 `psych-app` | 多阶段构建，运行镜像只有 JRE；`backend/config` 随镜像分发 |
| PostgreSQL 16 + pgvector | 容器 `psych-postgres` | 知识库、会话、轨迹都落这里；端口映射到 `15432` |
| Ollama（bge-m3 / qwen3-8b） | **可选**叠层 `docker-compose.ai.yml` | 模型层，`--ai` 才起，就是你说的要单独排除的那层 |
| Caddy（反代 / TLS） | **可选**叠层 `docker-compose.edge.yml` | 对外暴露时才需要；`--edge` 起，默认自签证书 |
| 语音转写 ASR | 宿主机 | 走 `psych.asr.command` 进程钩子，用 Windows 语音包；容器里留空即关闭，接口回 503 |
| 评测 / 压测 / 语料导入 | 宿主机脚本 | 是一次性任务，不是常驻服务 |

要真模型：`bash scripts/stack.sh up --ai`（Windows：`start.cmd ai`），会再起容器内 Ollama，
拉 `bge-m3`（1.2 GB）与 `qwen3-8b`（5 GB），对话与向量都接过去。**本机 jar 档默认接宿主 Ollama**。
国内网络拉不动 Docker Hub 时，在 `.env` 里设 `DOCKER_MIRROR=docker.m.daocloud.io`（脚本会先经镜像源拉取再打回标准标签），或给 Docker Desktop 配代理。细节与踩坑见 [deploy/README](deploy/README.md)。

### 方式二：本机 jar（没有 Docker 时）

```bash
cd backend
mvn -o package

export PSYCH_USER_SECRET="换成你自己的随机串，至少 16 字节"   # 签发会话令牌；不配也能起，但重启后旧令牌失效
export PSYCH_INTERNAL_TOKEN="dev-internal-token"

# 先起本地模型（默认档要它）：对话 qwen3-8b、向量 bge-m3
ollama serve
ollama pull bge-m3 && ollama pull qwen3-8b

# H2 文件库，重启数据还在；默认接 Ollama
java -jar psych-app/target/psych-app-0.6.0.jar --server.port=8099 --spring.profiles.active=h2

# 没有 Ollama / 只想看流程：加 stub 档，零依赖
# java -jar ... --spring.profiles.active=h2,stub
```

| 地址 | 是什么 |
|-|-|
| <http://localhost:8099/room/> | 产品界面（对话） |
| <http://localhost:8099/knowledge/> | 用户知识问答（纸本阅读风格） |
| <http://localhost:8099/account/> | 账号：注册、登录、退出（可选，不登录也能用） |
| <http://localhost:8099/internal> | 控制台：会话列表、阶段链、逐轮轨迹、待复核队列 |
| <http://localhost:8099/internal/settings.html> | 规则与参数：安全规则、用例、限流、系统话术、版本回滚 |
| <http://localhost:8099/internal/lexicon.html> | 敏感词库：整表编辑、试跑、恢复基线（保存即生效） |
| <http://localhost:8099/internal/accounts.html> | 账号视图：只读，看会话数与"需关注"轮次 |
| <http://localhost:8099/internal/ai.html> | 模型仪表盘：延迟、估算 token、失败数 |
| <http://localhost:8099/internal/review.html> | 临床审核记录：谁看过哪一版，改版自动重审 |
| <http://localhost:8099/internal/record.html?id=…> | 记录页：可打印/存 PDF，含过程、确认与边界声明 |
| <http://localhost:8099/internal/knowledge.html> | 知识库：切分预览、检索调试、问答试跑、入库与审核 |
| <http://localhost:8099/internal/flow.html> | 流程画布 |
| <http://localhost:8099/internal/policy.html> | 输出策略（禁句、拦截） |

开发档会把内部令牌自动填进页面，验收时不用手打；Docker 档默认不自动填（`PSYCH_INTERNAL_DEV_HINT=false`），令牌在 `stack.sh up` 结束时打印。
本机档默认已接 Ollama；接云端 OpenAI 兼容服务时用 `--spring.profiles.active=http` 并配 `psych.ai.*`。
前台用户侧（房间、知识问答）用纸本阅读风格；后台运营侧保留工具型控制台，两套不强行统一，见 [UI 设计规范](docs/04-规范/UI设计规范.md)。
本地向量可以走 Ollama 的 BGE-M3（免费、1024 维）：`ollama serve && ollama pull bge-m3`，再按 `application-http.yml` 里的 `PSYCH_AI_EMBEDDING_*` 配置；对话与向量允许不同源。

## 后台长什么样

![控制台：左边会话，中间阶段链，右边逐轮轨迹](docs/assets/current/console.png)

左栏是会话（含"待复核"标记），中间是这一轮走过的节点与分支，右侧是每轮用了哪条路、多久、是不是模板兜底。

![规则与参数：每条规则可改可删，下面是发布用例](docs/assets/current/settings.png)

规则与用例都可增删；保存前会逐条回放用例，失败会指出是哪一句不再符合预期。每次保存留快照，可对比当前、可回滚。

## 代码结构

| 模块 | 职责 |
|-|-|
| `psych-common` | 端口与模型：`CbtSessionStore`、`KnowledgeStore`、`AccountStore`、`ChatPort` 等接口和数据结构 |
| `psych-session` | 会话状态与轨迹（`CbtSession`、`CbtTurnEvent`） |
| `psych-knowledge` | 入库、切分、证据抽取 |
| `psych-rag` | 向量 + 全文混合检索、引用校验 |
| `psych-agent` | 流程执行（`CbtFlow`）、安全分流、配置存储 |
| `psych-infrastructure` | JDBC / 内存实现、模型适配器、输出闸门 |
| `psych-app` | HTTP 接口、页面、种子数据、装配 |

依赖方向单向：`app → agent/infrastructure → knowledge/rag/session → common`。换数据库或换模型只改适配器。

## 证据

| 项 | 结果 |
|-|-|
| 单元 + 集成测试 | 202 项通过（`mvn -o test`） |
| 检索链路 | 重排四档（none / lexical / fixture / http）接入，候选池 3× 再截断；5 个查询上改变了 4/5 的 top-1；两位独立标注者 top-1 一致率 1.0、逐条平均差 0.045 |
| 语料治理 | 同步按内容哈希去重（未变不重做）、改版自动回到待审、软删除立即从检索消失、ACL 在检索前过滤——均有测试锁住 |
| 运营闭环 | 文档审核留痕（版本 + 审核人 + 签字时 ACL 快照）与 `/internal/eval.html`（四格指标 + badcase 池 + 确定性抽样）。真实实例冒烟：越权提问拒答且零引用；只改权限不回填历史签字 |
| 组织与多实例 | 租户/团队/成员落库，写操作要求操作者令牌（署名来自令牌而非输入框），ACL 只接受本租户团队 id；审核记录、评测留痕、限流计数全部可落库共享。真实实例冒烟：无操作者 401、伪造团队 400、带身份签字 200 且署名正确 |
| 后台登录 | 操作者口令登录 + HttpOnly 会话 + CSRF 双提交 + 登出立刻失效；改口令即踢掉所有设备；登录成功/失败都留痕，失败按账号限流。冒烟：无 CSRF 写操作 403、带 CSRF 签字 200 且署名取自会话 |
| 容器档 | 一条命令起应用 + PG：建表、灌语料、检索、问答、重启后会话仍在，均已实跑 |
| 端到端 | 五阶段 → 行为实验分支 → 改说法确认 → 安全暂停 → 复核队列，跑通 |
| 重启存活 | 杀进程重启，同一令牌仍能读到上一轮 7 条轨迹、12 条消息；账号与会话一并存活 |
| 归属隔离 | 无令牌 401、伪造令牌 401、他人会话 404；账号令牌读不到他人未认领会话 |
| 后台门禁 | 改窄被用例拒绝、用例删空被拒、加词与新增规则立即生效 |
| 真实模型 | 本地 Ollama（qwen3-8b + bge-m3）：34 条正式集上回答率 / 拒答 / 关键词 / 引用有效率均 1.0（样本小）；CPU 生成 P50 22 秒 / P95 30 秒，向量 P50 112 ms |
| 并发 | 本机 PG 17 + HikariCP：128 并发 / 1280 请求，0 错误，P95 77 ms，约 650 rps（会话域）；pgvector 检索 64 并发 P95 535 ms（1024 维哈希向量，HNSW 未调） |

逐版说明与"还剩什么"在 [版本记录](docs/05-评估/版本记录.md)。

## 还没做到

- **真实模型只在 CPU 上跑过**：本地 Ollama（qwen3-8b + bge-m3）已接成默认档，34 条正式集跑通；
  但单轮 30–60 秒，没有 GPU / 云端 API 的延迟与成本对比；
- **容器档默认仍是桩**：`--ai` 才起容器内 Ollama；本机 jar 档默认接本地 Ollama，没有 Ollama 时用 `h2,stub`；
- **重排只用词法档跑过**：真 cross-encoder（bge-reranker）没联调；`rerank/agent-v1.json` 里的分是
  **子代理扮演模型**判的，只能证明管道通，不能当排序质量结论；
- **正式评估集仍小**：34 条示例语料题 + 25 条安全 + 8 条流程；WHO 20 条依赖私有语料；
  三组对照目前只跑小样本，不能当结论；
- **PostgreSQL/pgvector 并发压过一轮**：会话域 128 并发 0 错误；pgvector 检索 64 并发 P95 535 ms（1024 维哈希向量，HNSW 未调）；HNSW 参数仍未调；
- **账号补到"能自救"**：一次性恢复码、改口令、退出其他设备、注销即吊销都有；仍没有邮箱验证（没有 SMTP）与第三方登录；
- **共享设备的认领窗口已关**：登录后要用户点确认才认领；
- **临床审核仍是人的事**：有按版本的审核记录与清单，但默认"未审核"，系统不能替医生签字；
- **记录页没有外发通道**：一次性链接只能看一次、可撤回；打印后不再受系统保护；
- **语音**：Chrome/Edge 走浏览器识别；Firefox 走本机 Windows 语音包，Linux/容器默认没有；
- **紧急联系人仍不自动外呼/发短信**：提供 `tel:` / `sms:` 入口，发送键由本人按。

## 文档

| 想 | 去哪 |
|-|-|
| 五分钟了解背景与取舍 | [相关方案横向对比](docs/01-方案/02-相关方案横向对比.md) |
| 接手代码 | [模块拆分](docs/01-方案/03-模块拆分.md) → [API 契约](docs/02-设计/API契约.md) |
| 看界面规范 | [UI 设计规范](docs/04-规范/UI设计规范.md) → 实机 `/internal/knowledge.html` |
| 上线与运维 | [内部后台](docs/02-设计/内部后台.md) → [外部依赖约定](docs/04-规范/外部依赖与付费资源约定.md) |
| 看全部文档 | [docs/README](docs/README.md)（一页地图） |

## 边界

本项目不是医疗器械，不提供诊断、治疗或用药建议。它做的是把 CBT 里可自助的部分结构化，
并在识别到风险时把人导向专业帮助。

代码以仓库内 `LICENSE` 为准；示例语料仅供开发验证，不可作为临床依据。
