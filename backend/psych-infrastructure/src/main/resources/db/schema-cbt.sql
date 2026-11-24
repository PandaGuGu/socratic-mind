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
