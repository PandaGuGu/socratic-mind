-- 会话域：一次 CBT 会话、它的消息、它的轮次轨迹。
--
-- 三张表分开的理由（与 cbt-agent 的 checkpoint / 业务表分离同源）：
-- 1. cbt_session 是"现在停在哪儿、确认过什么"，读一次就能重建状态；
-- 2. cbt_message 是"当时说了什么"，要能按序导出给咨询师；
-- 3. cbt_turn 是"为什么这么走"，只给审计看，导出时可以不带上。
--
-- 只存 checkpoint 不存消息，会出现"能续跑但查不到当时说了什么"。
-- 字段一律可空，因为会话是逐步长出来的；非空只留给主键与时间。

CREATE TABLE IF NOT EXISTS cbt_session (
  id                    VARCHAR(64)  PRIMARY KEY,
  thread_id             VARCHAR(96)  NOT NULL,
  user_id               VARCHAR(64)  NOT NULL,
  stage                 VARCHAR(48)  NOT NULL,
  awaiting_user         BOOLEAN      NOT NULL DEFAULT FALSE,
  safety_status         VARCHAR(24),
  safety_reason         VARCHAR(400),
  target_problem        TEXT,
  situation             TEXT,
  automatic_thought     TEXT,
  emotions              TEXT,
  evidence_for          TEXT,
  evidence_against      TEXT,
  candidate_thought     TEXT,
  user_confirmed_thought TEXT,
  intervention_type     VARCHAR(48),
  action_plan           TEXT,
  action_result         TEXT,
  summary               TEXT,
  pending_question      TEXT,
  created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at            TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS cbt_message (
  session_id  VARCHAR(64) NOT NULL,
  seq         INT         NOT NULL,
  role        VARCHAR(16) NOT NULL,
  stage       VARCHAR(48) NOT NULL,
  content     TEXT,
  at          TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (session_id, seq)
);

CREATE TABLE IF NOT EXISTS cbt_turn (
  session_id  VARCHAR(64) NOT NULL,
  turn_no     INT         NOT NULL,
  action      VARCHAR(24) NOT NULL,
  stage_from  VARCHAR(48),
  stage_to    VARCHAR(48),
  status      VARCHAR(32),
  safety_rule VARCHAR(48),
  model_mode  VARCHAR(16),
  guard_rule  VARCHAR(48),
  latency_ms  BIGINT      NOT NULL DEFAULT 0,
  at          TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (session_id, turn_no)
);

CREATE INDEX IF NOT EXISTS idx_cbt_session_user ON cbt_session (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_cbt_message_session ON cbt_message (session_id);
CREATE INDEX IF NOT EXISTS idx_cbt_turn_session ON cbt_turn (session_id);

-- 增量列（v0.4.0）：老库只跑 CREATE TABLE IF NOT EXISTS 不会长出新列，
-- 因此把新增字段写成幂等的 ALTER。H2（PG 模式）与 PostgreSQL 都支持 ADD COLUMN IF NOT EXISTS。
-- 情绪自评、认知偏差标签、紧急联系人、危机事件处置时间，四组都属于"后加的可空信息"，
-- 空值就表示"用户没填/没评/没处理"，与"填了 0 分"能区分开。
ALTER TABLE cbt_session ADD COLUMN IF NOT EXISTS mood_points     TEXT;
ALTER TABLE cbt_session ADD COLUMN IF NOT EXISTS distortions     TEXT;
ALTER TABLE cbt_session ADD COLUMN IF NOT EXISTS contact_name    VARCHAR(64);
ALTER TABLE cbt_session ADD COLUMN IF NOT EXISTS contact_phone   VARCHAR(32);
ALTER TABLE cbt_session ADD COLUMN IF NOT EXISTS contact_relation VARCHAR(32);
ALTER TABLE cbt_session ADD COLUMN IF NOT EXISTS safety_ack_at   TIMESTAMP WITH TIME ZONE;
ALTER TABLE cbt_session ADD COLUMN IF NOT EXISTS safety_ack_by   VARCHAR(64);

-- 账号（v0.5.0）：只放"能证明你是你"的最小集合。
-- 心理记录仍归 owner_id（设备），account_id 是第二把钥匙；两张表不合并是刻意的：
-- 账号可以注销，会话不必随之消失；会话也可以不属任何账号（匿名模式）。
CREATE TABLE IF NOT EXISTS app_account (
  id             VARCHAR(64)  PRIMARY KEY,
  email          VARCHAR(254) NOT NULL,
  password_hash  VARCHAR(255) NOT NULL,
  display_name   VARCHAR(64),
  created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
  last_login_at  TIMESTAMP WITH TIME ZONE
);

-- 邮箱统一按小写存（应用层归一化），唯一索引是并发注册的最后一道闸。
CREATE UNIQUE INDEX IF NOT EXISTS ux_app_account_email ON app_account (email);

-- 账号生命周期（v0.7.0）：令牌代数 + 一次性恢复码。
-- token_epoch：改口令 / 重置口令 / 退出其他设备时 +1；令牌里签着 epoch，旧令牌立刻失效。
-- recovery_hash：注册时生成一次恢复码，只存 PBKDF2 摘要；忘了口令时用它重置，不依赖发信通道。
ALTER TABLE app_account ADD COLUMN IF NOT EXISTS token_epoch INT NOT NULL DEFAULT 0;
ALTER TABLE app_account ADD COLUMN IF NOT EXISTS recovery_hash VARCHAR(255);
ALTER TABLE app_account ADD COLUMN IF NOT EXISTS recovery_used_at TIMESTAMP WITH TIME ZONE;

-- 账号级的紧急联系人（v0.7.1）：登录后在账号页填一次，新开的一轮自动带上。
-- 只用于安全暂停时的危机卡片；系统不据此评级、不外呼、不发送。会话里仍可单独覆盖。
ALTER TABLE app_account ADD COLUMN IF NOT EXISTS contact_name     VARCHAR(64);
ALTER TABLE app_account ADD COLUMN IF NOT EXISTS contact_phone    VARCHAR(32);
ALTER TABLE app_account ADD COLUMN IF NOT EXISTS contact_relation VARCHAR(32);

ALTER TABLE cbt_session ADD COLUMN IF NOT EXISTS account_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_cbt_session_account ON cbt_session (account_id, created_at DESC);

-- 一次性分享链接（v0.7.0）：把一轮记录交给某个人看，而不是交给整个互联网。
-- 只存 token 的 SHA-256，不存明文；used_at 非空即已看过，revoked_at 非空即被本人撤回。
-- 过期、已用、已撤销三种状态都表现为"这条链接不再可用"，不区分具体原因。
CREATE TABLE IF NOT EXISTS share_link (
  token_hash    VARCHAR(64) PRIMARY KEY,
  session_id    VARCHAR(64) NOT NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at    TIMESTAMP WITH TIME ZONE NOT NULL,
  used_at       TIMESTAMP WITH TIME ZONE,
  revoked_at    TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_share_link_session ON share_link (session_id);

-- 在线评测（v0.6.0）：把"每一轮到底发生了什么"留痕，并把可疑样本挑出来。
--
-- 两张表刻意分开：
-- 1. eval_query_log 是连续的——每轮一条，用来算回答率、拒答率、引用有效率与延迟分位；
-- 2. eval_badcase 是离散的——人真正要逐条看的那几条。
-- 合成一张表会让"算率"的查询扫过所有大文本字段，也会让"看样本"的查询被正常轮次淹没。
--
-- sufficient 与 citations_valid 分开存，是因为它们坏在不同阶段：
-- 前者是检索前的判定，后者是生成后的校验。合成一个"是否成功"就再也分不清该修哪一段。
CREATE TABLE IF NOT EXISTS eval_query_log (
  id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  query_text      TEXT             NOT NULL,
  status          VARCHAR(24)      NOT NULL,
  sufficient      BOOLEAN          NOT NULL,
  citations_valid BOOLEAN          NOT NULL,
  coverage        DOUBLE PRECISION NOT NULL,
  hits            INT              NOT NULL,
  latency_ms      BIGINT           NOT NULL,
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_eval_query_log_at ON eval_query_log (created_at DESC);

CREATE TABLE IF NOT EXISTS eval_badcase (
  id          VARCHAR(64)  PRIMARY KEY,
  query_text  TEXT         NOT NULL,
  answer      TEXT,
  reason      VARCHAR(200),
  source      VARCHAR(16)  NOT NULL,
  created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
  resolved    BOOLEAN      NOT NULL DEFAULT FALSE,
  resolved_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_eval_badcase_open ON eval_badcase (resolved, created_at DESC);

-- 组织域（v0.7.0）：租户 → 团队 → 成员。
--
-- 为什么必须落库而不是留在配置里：多实例部署时，每个实例各读一份 JSON，
-- "谁签的字"就会随负载均衡漂到不同副本，同一个团队的人在不同机器上看到不同权限。
--
-- 三层是够用的最小结构：租户隔离数据边界，团队是可见范围的单位，成员是签字的责任主体。
-- 成员暂时只属一个团队（team_id 单值）：多团队归属要改的是"可见范围求并集"，
-- 而现在求并集的收益还不如先把身份做实。
CREATE TABLE IF NOT EXISTS org_tenant (
  id          VARCHAR(64)  PRIMARY KEY,
  name        VARCHAR(128) NOT NULL,
  created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS org_team (
  id          VARCHAR(64)  PRIMARY KEY,
  tenant_id   VARCHAR(64)  NOT NULL,
  name        VARCHAR(128) NOT NULL,
  created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_org_team_tenant ON org_team (tenant_id);

-- token_hash：操作者令牌的 SHA-256。只存摘要，库被读走也拿不到可用的钥匙。
-- revoked_at 非空即失效——人走了不需要删行，删行会让历史签字失去署名主体。
CREATE TABLE IF NOT EXISTS org_member (
  id          VARCHAR(64)  PRIMARY KEY,
  tenant_id   VARCHAR(64)  NOT NULL,
  team_id     VARCHAR(64),
  name        VARCHAR(64)  NOT NULL,
  role        VARCHAR(16)  NOT NULL,
  token_hash  VARCHAR(64)  NOT NULL,
  created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
  revoked_at  TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_org_member_token ON org_member (token_hash);

-- 文档审核记录（v0.7.0）：从 JSON 文件搬进库。
--
-- 主键是"文档 + 版本"：不同版本各留一条历史，同一版本重审覆盖。
-- acl_snapshot 存签字那一刻的可见范围，之后改权限不回填——回填等于让历史签字随现状漂移。
CREATE TABLE IF NOT EXISTS kb_document_review (
  document_id   VARCHAR(64)  NOT NULL,
  version       VARCHAR(16)  NOT NULL,
  title         VARCHAR(256),
  decision      VARCHAR(16)  NOT NULL,
  operator_id   VARCHAR(64),
  operator_name VARCHAR(64),
  acl_snapshot  VARCHAR(512),
  note          VARCHAR(1000),
  reviewed_at   TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (document_id, version)
);

CREATE INDEX IF NOT EXISTS idx_kb_review_at ON kb_document_review (reviewed_at DESC);

-- 多实例限流（v0.7.0）：键 = 限流维度 + 窗口起点，值 = 已用次数。
-- 每次请求一次 upsert，用数据库的原子性换掉进程内的 synchronized。
CREATE TABLE IF NOT EXISTS rate_limit_counter (
  bucket      VARCHAR(160) PRIMARY KEY,
  hits        INT          NOT NULL,
  expires_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rate_limit_expiry ON rate_limit_counter (expires_at);

-- 后台操作者登录（v0.7.1）
--
-- 之前后台只有一把共享令牌：能证明"来自内部"，证明不了"你是谁"，也没有登录与登出。
-- 这里补三样：口令、会话、失败留痕。
--
-- 三列都是后加的，所以用幂等 ALTER；老库里已有成员不会被清掉，
-- 只是没有口令——他们仍然可以用原来的操作者令牌走 API。
ALTER TABLE org_member ADD COLUMN IF NOT EXISTS username      VARCHAR(64);
ALTER TABLE org_member ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);
-- session_epoch：改口令时 +1。会话里签着 epoch，旧会话立刻失效，不用逐条删。
ALTER TABLE org_member ADD COLUMN IF NOT EXISTS session_epoch INT NOT NULL DEFAULT 0;

-- 登录名全局唯一（不按租户分段）：登录框只让填一个名字，多一层租户选择对内部工具是负担。
CREATE UNIQUE INDEX IF NOT EXISTS ux_org_member_username ON org_member (username);

-- 会话落库而不是签在 cookie 里：登出要能立刻生效。
-- 自签名的 cookie 只能靠过期时间失效，"点了登出但令牌还能用 2 小时"是不可接受的。
CREATE TABLE IF NOT EXISTS org_session (
  sid_hash    VARCHAR(64) PRIMARY KEY,
  member_id   VARCHAR(64) NOT NULL,
  csrf_token  VARCHAR(64) NOT NULL,
  epoch       INT         NOT NULL,
  created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
  revoked_at  TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_org_session_member ON org_session (member_id);

-- 登录留痕：成功也要记。只记失败的话，出了事无法回答"这个账号最后一次正常登录是什么时候"。
CREATE TABLE IF NOT EXISTS org_login_attempt (
  id        BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  username  VARCHAR(64) NOT NULL,
  ok        BOOLEAN     NOT NULL,
  reason    VARCHAR(64),
  at        TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_login_attempt_at ON org_login_attempt (at DESC);
