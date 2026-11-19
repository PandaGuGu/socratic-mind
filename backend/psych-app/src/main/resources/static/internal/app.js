const API = "/api/internal";

const STAGES = [
  { id: "PROBLEM_IDENTIFICATION", name: "情境", goal: "选定一个具体事件" },
  { id: "COGNITIVE_EXTRACTION", name: "想法", goal: "事实、自动想法、情绪" },
  { id: "COGNITIVE_REVIEW", name: "检验", goal: "证据与另一种解释" },
  { id: "INTERVENTION_DECISION", name: "行动", goal: "选一种干预方式" },
  { id: "ACTION_CONFIRM", name: "确认", goal: "中断点：等你确认" },
  { id: "ACTION_REVIEW", name: "复盘", goal: "结果与新信息" },
  { id: "SESSION_SUMMARY", name: "总结", goal: "存下这一轮" },
  { id: "SAFETY_HOLD", name: "安全暂停", goal: "转介并停止普通流程" }
];

const STATUS_LABEL = {
  awaiting_user: "等回答",
  awaiting_confirmation: "等确认",
  safety_hold: "安全暂停",
  ended: "已结束",
  running: "进行中"
};

const ACTION_LABEL = {
  start: "开始",
  answer: "回答",
  blocked: "被拦住",
  safety_hold: "安全暂停",
  confirm: "用户确认",
  end: "结束"
};

const ACTION_CLASS = {
  safety_hold: "is-safety",
  blocked: "is-blocked",
  confirm: "is-confirm"
};

const GUARD_LABEL = {
  banned: "禁句",
  min_chars: "太短",
  max_chars: "太长",
  novelty: "疑似编造",
  empty: "空输出"
};

const FIELD_ORDER = [
  ["sessionId", "会话"],
  ["threadId", "线程"],
  ["stage", "阶段", (v, s) => `${v} · ${s.stageLabel || ""}`],
  ["status", "状态"],
  ["visibleIndex", "可见段位"],
  ["targetProblem", "目标问题"],
  ["automaticThought", "自动想法"],
  ["candidateThought", "候选说法（系统提议）"],
  ["userConfirmedThought", "用户确认的说法", null, "is-key"],
  ["interventionType", "干预类型"],
  ["actionPlan", "行动计划"],
  ["actionResult", "行动结果"],
  ["summary", "本轮总结"],
  ["awaitingUser", "待确认"],
  ["safetyStatus", "安全状态", null, "is-refer"],
  ["createdAt", "创建时间", (v) => stampOf(v)]
];

const S = {
  overview: null,
  sessions: [],
  selected: null,
  detail: null,
  filter: "all",
  tab: "trace",
  openStage: null,
  polling: null
};

const $ = (id) => document.getElementById(id);

function esc(value) {
  return String(value == null ? "" : value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

const EXTRA_LABELS = {
  BEHAVIORAL_EXPERIMENT: "行为实验",
  PRACTICAL_PROBLEM_SOLVING: "问题解决",
  CONDITION: "判断",
  ENDED: "结束"
};

function stageLabel(id) {
  const found = STAGES.find((s) => s.id === id);
  return found ? found.name : EXTRA_LABELS[id] || id || "—";
}

function timeOf(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return d.toLocaleTimeString("zh-CN", { hour12: false });
}

function dateOf(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")} ${timeOf(iso).slice(0, 5)}`;
}

function stampOf(iso) {
  if (!iso) return "—";
  return `${dateOf(iso)}:${timeOf(iso).slice(6, 8)}`;
}

async function getJson(url, options) {
  const res = await fetch(url, options);
  if (!res.ok) {
    const err = new Error(`${res.status} ${res.statusText}`);
    err.status = res.status;
    throw err;
  }
  return res.json();
}

function toast(message, isError) {
  const el = $("toast");
  el.textContent = message;
  el.hidden = false;
  el.classList.toggle("is-error", Boolean(isError));
  clearTimeout(toast._timer);
  toast._timer = setTimeout(() => {
    el.hidden = true;
  }, 2600);
}

/* ---------------- 顶部运行时标识 ---------------- */

function renderBadges(o) {
  const badges = [];
  badges.push(
    o.durable
      ? `<span class="badge is-ok"><i></i>已落盘 · 重启可续</span>`
      : `<span class="badge is-warn"><i></i>内存档 · 重启即丢</span>`
  );
  badges.push(
    o.stubModel
      ? `<span class="badge is-warn"><i></i>桩模型 ${esc(o.aiProvider)}</span>`
      : `<span class="badge is-ok"><i></i>${esc(o.aiProvider)}${o.chatModel ? " · " + esc(o.chatModel) : ""}</span>`
  );
  badges.push(
    o.authEnabled
      ? `<span class="badge is-ok"><i></i>已鉴权</span>`
      : `<span class="badge is-warn"><i></i>无鉴权 · 仅本机</span>`
  );
  badges.push(`<span class="badge is-quiet"><i></i>${o.sessions} 会话 · ${o.events} 事件</span>`);
  if (o.policyVersion) {
    badges.push(`<span class="badge is-quiet"><i></i>策略 v${o.policyVersion}</span>`);
  }
  if (o.flowVersion) {
    badges.push(`<span class="badge is-quiet"><i></i>流程 v${o.flowVersion}</span>`);
  }
  if (o.guardRejections > 0) {
    badges.push(`<span class="badge is-warn"><i></i>拦截 ${o.guardRejections} 次</span>`);
  }
  if (o.safetyHolds > 0) {
    badges.push(`<span class="badge is-warn"><i></i>安全暂停 ${o.safetyHolds}</span>`);
  }
  if (o.pendingReview > 0) {
    badges.push(`<span class="badge is-warn"><i></i>待复核 ${o.pendingReview}</span>`);
  }
  $("badges").innerHTML = badges.join("");
  $("storeNote").textContent = o.durable
    ? `存储：${o.store}（会话、消息、轮次轨迹均已落库）`
    : "内存档：进程重启后会话与轨迹全部清空，仅用于开发与演示。";
}

/* ---------------- 会话列表 ---------------- */

function visibleSessions() {
  return S.sessions.filter((s) => {
    if (S.filter === "safety") return s.stage === "SAFETY_HOLD" || s.safetyStatus === "review";
    if (S.filter === "awaiting") return s.awaiting;
    if (S.filter === "demo") return s.demo;
    return true;
  });
}

function renderSessions() {
  const rows = visibleSessions()
    .slice()
    .sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)));
  $("sessionCount").textContent = String(S.sessions.length);

  if (!S.sessions.length) {
    $("sessionList").innerHTML = `<p class="panel-foot" style="border:none;padding:10px 6px">还没有会话。点右上角生成一条演示会话，或从产品接口创建。</p>`;
    return;
  }
  if (!rows.length) {
    $("sessionList").innerHTML = `<p class="panel-foot" style="border:none;padding:10px 6px">这个筛选下没有会话。</p>`;
    return;
  }

  $("sessionList").innerHTML = rows
    .map((s) => {
      const mark = s.stage === "SAFETY_HOLD" ? "is-hold" : s.awaiting ? "is-await" : s.demo ? "is-demo" : "";
      const flags = [
        s.demo ? '<span class="tag is-plain">演示</span>' : "",
        s.awaiting ? '<span class="tag is-plain">等确认</span>' : "",
        s.stage === "SAFETY_HOLD" ? '<span class="tag is-refer">安全暂停</span>' : ""
      ].join("");
      return `
        <button class="session-item ${S.selected === s.sessionId ? "is-on" : ""}" data-id="${esc(s.sessionId)}" type="button">
          <span class="session-top">
            <span class="dot-mark ${mark}"></span>
            <span class="session-user">${esc(s.userId)}</span>
            <span class="session-stage">${esc(s.stageLabel)}</span>
          </span>
          <span class="session-meta">
            <span class="session-id">${esc(s.sessionId)}</span>
            <span>· ${s.turns} 轮</span>
            <span style="margin-left:auto">${dateOf(s.updatedAt)}</span>
          </span>
          ${flags ? `<span class="session-meta">${flags}</span>` : ""}
        </button>`;
    })
    .join("");

  $("sessionList")
    .querySelectorAll(".session-item")
    .forEach((btn) =>
      btn.addEventListener("click", () => {
        select(btn.dataset.id);
      })
    );
}

/* ---------------- 链路轨道 ---------------- */

function deriveStates(detail) {
  const visited = new Set();
  detail.trace.forEach((e) => {
    if (e.stageFrom) visited.add(e.stageFrom);
    if (e.stageTo) visited.add(e.stageTo);
  });
  const RAIL_ALIAS = {
    BEHAVIORAL_EXPERIMENT: "INTERVENTION_DECISION",
    PRACTICAL_PROBLEM_SOLVING: "INTERVENTION_DECISION",
    CONDITION: "INTERVENTION_DECISION"
  };
  const current = RAIL_ALIAS[detail.session.stage] || detail.session.stage;
  const awaiting = detail.session.awaitingUser;
  const ended = detail.session.status === "ended";
  const map = {};
  STAGES.forEach((st) => {
    if (st.id === current) {
      if (st.id === "SAFETY_HOLD") {
        map[st.id] = "hold";
      } else if (awaiting) {
        map[st.id] = "wait";
      } else {
        map[st.id] = ended ? "done" : "active";
      }
    } else if (visited.has(st.id)) {
      map[st.id] = "done";
    } else {
      map[st.id] = "idle";
    }
  });
  return map;
}

const STATE_TAG = {
  done: "已完成",
  active: "进行中",
  wait: "等确认",
  hold: "已触发",
  idle: "未进入"
};

function messagesOfStage(detail, stageId) {
  return (detail.messages || []).filter((m) => m.stage === stageId);
}

function renderRail() {
  if (!S.detail) {
    $("stageName").textContent = "未选择会话";
    $("stageSub").textContent = "从左侧选一条会话，查看它走过的节点";
    $("stagePill").hidden = true;
    $("stageDot").className = "dot";
    $("progress").innerHTML = "";
    $("chain").innerHTML = "";
    $("legend").innerHTML = "";
    return;
  }

  const { session, trace } = S.detail;
  const states = deriveStates(S.detail);
  const awaiting = session.awaitingUser;
  const hold = session.stage === "SAFETY_HOLD";
  const ended = session.status === "ended";
  const currentStage = session.stage;

  const pillText = hold ? "已触发" : awaiting ? "等你确认" : ended ? "已结束" : "进行中";
  const pillClass = hold ? "is-hold" : awaiting ? "is-wait" : "";
  const dotClass = hold ? "is-hold" : awaiting ? "is-wait" : ended ? "" : "is-live";

  $("stageName").textContent = currentStage === "ENDED" ? "已结束" : stageLabel(currentStage);
  $("stageSub").textContent = `会话 ${session.sessionId} · ${trace.length} 条轨迹 · ${(S.detail.messages || []).length} 条消息`;
  $("stagePill").hidden = false;
  $("stagePill").className = `pill ${pillClass}`;
  $("stagePill").textContent = pillText;
  $("stageDot").className = `dot ${dotClass}`;

  $("progress").innerHTML = STAGES.map((st) => {
    const state = states[st.id];
    const cls = state === "hold" ? "is-hold" : state === "done" ? "is-done" : state === "active" || state === "wait" ? "is-active" : "";
    return `<span class="${cls}"></span>`;
  }).join("");

  $("chain").innerHTML = STAGES.map((st, index) => {
    const state = states[st.id];
    const isOpen = S.openStage === st.id;
    const msgs = messagesOfStage(S.detail, st.id);
    const tag = STATE_TAG[state] || "";
    const branchHost = st.id === "INTERVENTION_DECISION" && (S.detail.branches || []).length > 0;

    const body = isOpen
      ? `<div class="card-body">${
          msgs.length
            ? msgs
                .map(
                  (m) => `<div class="said ${m.role === "assistant" ? "is-agent" : ""}">
                    <b>${m.role === "assistant" ? "引导" : "你"}</b>
                    <span>${esc(m.content)}</span>
                  </div>`
                )
                .join("")
            : `<div class="said is-empty">这一格没有留下消息。</div>`
        }</div>`
      : "";

    const branches = branchHost
      ? `<ul class="branches">${S.detail.branches
          .map(
            (b) => `<li class="branch ${b.taken ? "is-taken" : ""}">${esc(b.label)}</li>`
          )
          .join("")}</ul>`
      : "";

    const pin = state === "done" ? checkIcon() : String(index + 1);

    return `
      <li class="node is-${state}">
        <span class="pin">${pin}</span>
        <div class="card ${isOpen ? "is-open" : ""}" data-stage="${st.id}" role="button" tabindex="0">
          <div class="card-top">
            <span class="card-name">${esc(st.name)}</span>
            <span class="card-tag">${tag}</span>
          </div>
          <div class="card-goal">${esc(st.goal)}</div>
          ${branches}
          ${body}
        </div>
      </li>`;
  }).join("");

  $("chain")
    .querySelectorAll(".card")
    .forEach((card) => {
      const toggle = () => {
        const id = card.dataset.stage;
        S.openStage = S.openStage === id ? null : id;
        renderRail();
      };
      card.addEventListener("click", toggle);
      card.addEventListener("keydown", (e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          toggle();
        }
      });
    });

  $("legend").innerHTML = [
    ["lg-done", "已完成"],
    ["lg-active", "进行中"],
    ["lg-wait", "等确认"],
    ["lg-idle", "未进入"],
    ["lg-hold", "安全暂停"],
    ["lg-skip", "未走这条"]
  ]
    .map(([cls, text]) => `<span><i class="lg ${cls}"></i>${text}</span>`)
    .join("");
}

function checkIcon() {
  return `<svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 12.5l5 5L20 6.5"></path></svg>`;
}

/* ---------------- 右侧详情 ---------------- */

function renderDetail() {
  const meta = $("detailMeta");
  if (!S.detail) {
    meta.innerHTML = "";
    $("detailBody").innerHTML = `
      <div class="empty">
        <h3>还没有选会话</h3>
        <p>这个页面只读取会话状态与轨迹，不写任何业务字段。<br>确认动作仍然只能由用户从产品接口发起。</p>
        <div class="empty-actions">
          <button class="btn btn-primary" data-demo="flow" type="button">生成流程演示</button>
          <button class="btn" data-demo="safety" type="button">生成安全演示</button>
        </div>
      </div>`;
    bindDemo($("detailBody"));
    return;
  }

  const { session } = S.detail;
  meta.innerHTML = `<span>${esc(session.sessionId)}</span><span>· ${esc(session.stage)}</span>`;

  if (S.tab === "trace") {
    $("detailBody").innerHTML = renderTrace();
  } else if (S.tab === "fields") {
    $("detailBody").innerHTML = renderFields();
  } else if (S.tab === "messages") {
    $("detailBody").innerHTML = renderMessages();
  } else {
    $("detailBody").innerHTML = renderRaw();
    const copy = $("copyRaw");
    if (copy) {
      copy.addEventListener("click", async () => {
        try {
          await navigator.clipboard.writeText(JSON.stringify(S.detail, null, 2));
          toast("已复制");
        } catch (e) {
          toast("复制失败，请手动选择", true);
        }
      });
    }
  }
}

function renderTrace() {
  const { trace } = S.detail;
  if (!trace.length) {
    return `<div class="notice">这条会话没有轨迹。若它来自旧版本进程，重启前产生的数据没有事件记录。</div>`;
  }
  return `
    <p class="section-label">轮次轨迹 · 共 ${trace.length} 条</p>
    <div class="events">
      ${trace
        .map((e) => {
          const cls = ACTION_CLASS[e.action] || "";
          const tags = [];
          if (e.status) tags.push(`<span class="tag">${esc(STATUS_LABEL[e.status] || e.status)}</span>`);
          if (e.safetyRule) tags.push(`<span class="tag is-refer">${esc(e.safetyRule)}</span>`);
          if (e.modelMode === "model") tags.push(`<span class="tag is-model">模型生成</span>`);
          if (e.modelMode === "template") tags.push(`<span class="tag is-template">模板兜底</span>`);
          if (e.guardRule) tags.push(`<span class="tag is-refer">策略拦截：${esc(GUARD_LABEL[e.guardRule] || e.guardRule)}</span>`);
          if (e.latencyMs > 0) tags.push(`<span class="tag">${e.latencyMs} ms</span>`);
          const from = e.stageFrom ? stageLabel(e.stageFrom) : "—";
          const to = stageLabel(e.stageTo);
          return `
            <div class="ev ${cls}">
              <div class="ev-head">
                <span class="ev-no">${String(e.turnNo).padStart(2, "0")}</span>
                <span class="ev-action">${esc(ACTION_LABEL[e.action] || e.action)}</span>
                <span class="ev-time">${timeOf(e.at)}</span>
              </div>
              <div class="ev-flow">${esc(from)}<span class="arrow">→</span>${esc(to)}</div>
              <div class="ev-tags">${tags.join("")}</div>
            </div>`;
        })
        .join("")}
    </div>`;
}

function renderFields() {
  const session = S.detail.session;
  return `
    <p class="section-label">字段快照 · 只读</p>
    <div class="fields">
      ${FIELD_ORDER.map(([key, label, format, extraClass]) => {
        const raw = session[key];
        const isNull = raw === null || raw === undefined || raw === "";
        const value = isNull ? "—" : format ? format(raw, session) : raw;
        const keyClass = key === "userConfirmedThought" ? "is-key" : key === "safetyStatus" && session.safetyStatus === "hold" ? "is-refer" : extraClass || "";
        return `
          <div class="field ${keyClass}">
            <dt>${esc(key)}<span style="float:right;font-family:var(--sans)">${esc(label)}</span></dt>
            <dd class="${isNull ? "is-null" : ""}">${esc(value)}</dd>
          </div>`;
      }).join("")}
    </div>`;
}

function renderMessages() {
  const messages = S.detail.messages || [];
  if (!messages.length) {
    return `<div class="notice">这条会话还没有消息。</div>`;
  }
  return `
    <p class="section-label">消息 · 共 ${messages.length} 条</p>
    ${messages
      .map(
        (m) => `
      <div class="msg ${m.role === "assistant" ? "is-agent" : ""}">
        <div class="msg-head">
          <span class="msg-role">${m.role === "assistant" ? "引导" : "你"}</span>
          <span>${esc(stageLabel(m.stage))}</span>
          <span class="msg-time">${timeOf(m.at)}</span>
        </div>
        <div class="msg-text">${esc(m.content)}</div>
      </div>`
      )
      .join("")}`;
}

function renderRaw() {
  return `
    <div class="raw-head"><button class="btn" id="copyRaw" type="button">复制 JSON</button></div>
    <pre>${esc(JSON.stringify(S.detail, null, 2))}</pre>`;
}

/* ---------------- 数据加载 ---------------- */

async function loadOverview() {
  S.overview = await getJson(`${API}/overview`);
  renderBadges(S.overview);
}

async function loadSessions(silent) {
  S.sessions = await getJson(`${API}/sessions`);
  renderSessions();
  if (!silent && S.selected && !S.sessions.some((s) => s.sessionId === S.selected)) {
    S.selected = null;
    S.detail = null;
    renderRail();
    renderDetail();
  }
}

async function select(id) {
  S.selected = id;
  S.openStage = null;
  renderSessions();
  try {
    const url = new URL(location.href);
    url.searchParams.set("session", id);
    history.replaceState(null, "", url);
  } catch (e) {
    /* file:// 或 sandbox 下不可写历史，不影响选择 */
  }
  try {
    S.detail = await getJson(`${API}/sessions/${encodeURIComponent(id)}`);
    renderRail();
    renderDetail();
  } catch (e) {
    S.detail = null;
    renderRail();
    $("detailBody").innerHTML = `<div class="notice">读取失败：${esc(e.message)}。会话可能已随进程重启消失。</div>`;
  }
}

async function createDemo(kind) {
  try {
    const res = await getJson(`${API}/demo`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ kind })
    });
    await loadOverview();
    await loadSessions(true);
    await select(res.sessionId);
    toast(kind === "safety" ? "已生成安全演示（真实流程）" : "已生成流程演示（真实流程）");
  } catch (e) {
    toast(`生成失败：${e.message}`, true);
  }
}

function bindDemo(root) {
  root.querySelectorAll("[data-demo]").forEach((btn) =>
    btn.addEventListener("click", () => createDemo(btn.dataset.demo))
  );
}

/* ---------------- 事件绑定 ---------------- */

$("refreshBtn").addEventListener("click", async () => {
  try {
    await loadOverview();
    await loadSessions(true);
    if (S.selected) {
      S.detail = await getJson(`${API}/sessions/${encodeURIComponent(S.selected)}`);
      renderRail();
      renderDetail();
    }
    toast("已刷新");
  } catch (e) {
    toast(`刷新失败：${e.message}`, true);
  }
});

$("demoFlowBtn").addEventListener("click", () => createDemo("flow"));
$("demoSafetyBtn").addEventListener("click", () => createDemo("safety"));

$("filters").addEventListener("click", (e) => {
  const btn = e.target.closest(".filter");
  if (!btn) return;
  S.filter = btn.dataset.filter;
  $("filters")
    .querySelectorAll(".filter")
    .forEach((f) => f.classList.toggle("is-on", f === btn));
  renderSessions();
});

$("tabs").addEventListener("click", (e) => {
  const btn = e.target.closest(".tab");
  if (!btn) return;
  S.tab = btn.dataset.tab;
  $("tabs")
    .querySelectorAll(".tab")
    .forEach((t) => t.classList.toggle("is-on", t === btn));
  const url = new URL(location.href);
  url.searchParams.set("tab", S.tab);
  history.replaceState(null, "", url);
  renderDetail();
});

/* ---------------- 启动 ---------------- */

async function boot() {
  try {
    const params = new URLSearchParams(location.search);
    const wantedTab = params.get("tab");
    if (wantedTab && ["trace", "fields", "messages", "raw"].includes(wantedTab)) {
      S.tab = wantedTab;
      $("tabs")
        .querySelectorAll(".tab")
        .forEach((t) => t.classList.toggle("is-on", t.dataset.tab === wantedTab));
    }
    await loadOverview();
    await loadSessions(true);
    renderRail();
    renderDetail();
    const wanted = params.get("session");
    if (wanted) {
      await select(wanted);
    } else if (S.sessions.length) {
      const first = S.sessions.slice().sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)))[0];
      await select(first.sessionId);
    }
    S.polling = setInterval(async () => {
      try {
        await loadOverview();
        await loadSessions(true);
      } catch (e) {
        /* 轮询失败不打断当前视图 */
      }
    }, 6000);
  } catch (e) {
    $("badges").innerHTML = `<span class="badge is-warn"><i></i>接口不可达</span>`;
    $("detailBody").innerHTML = `
      <div class="empty">
        <h3>读不到内部接口</h3>
        <p>${esc(e.message)}<br>
        确认应用已启动，并且 <code style="font-family:var(--mono)">psych.internal.enabled=true</code>。
        该开关关闭时，/api/internal 与 /internal 都不会注册。</p>
      </div>`;
  }
}

boot();
