/**
 * 审核与心理复核页。
 *
 * 两部分：
 * 1. 配置审核：安全规则、词库、流程画布、输出策略、运行参数，按版本签字，保留历史；
 * 2. 会话复核：只看触发安全关注/暂停的会话，读对话、写结论、标记是否已上报。
 *
 * 系统不替人签字，也不会自动通知任何人；它只保证"没签过 / 没复核"这件事是显式的。
 */
(function () {
  var API = "/api/internal/reviews";
  var $ = function (id) { return document.getElementById(id); };

  var STATE = {
    unreviewed: "未审核",
    stale: "版本已变，需重审",
    approved: "已通过",
    changes_requested: "需修改",
    ok: "未见明显风险",
    watch: "持续关注",
    refer: "建议转介",
    urgent: "紧急上报"
  };
  var KIND = {
    safety: "安全规则",
    lexicon: "敏感词库",
    flow: "流程画布与系统话术",
    policy: "输出策略",
    limits: "运行参数",
    session: "会话复核"
  };
  var SESSION_STATUS = { ok: "ok", watch: "watch", refer: "refer", urgent: "urgent" };

  var rows = [];
  var history = [];
  var sessions = [];
  var selectedSession = null;
  var me = null;

  function esc(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function date(v) {
    if (!v) return "—";
    var d = new Date(v);
    if (isNaN(d.getTime())) return String(v);
    var p = function (n) { return (n < 10 ? "0" : "") + n; };
    return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate())
      + " " + p(d.getHours()) + ":" + p(d.getMinutes());
  }

  function getJson(url) {
    return fetch(url, { headers: { Accept: "application/json" } }).then(function (res) {
      return res.json().catch(function () { return {}; }).then(function (data) {
        if (!res.ok) throw new Error(data.message || data.error || ("读取失败（" + res.status + "）"));
        return data;
      });
    });
  }

  function postJson(url, method, body) {
    return fetch(url, {
      method: method || "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(body || {})
    }).then(function (res) {
      return res.json().catch(function () { return {}; }).then(function (data) {
        if (!res.ok) throw new Error(data.message || data.error || ("请求失败（" + res.status + "）"));
        return data;
      });
    });
  }

  function pill(state, text) {
    return '<span class="tag-pill ' + esc(state) + '">' + esc(text || STATE[state] || state) + "</span>";
  }

  function render(data) {
    rows = data.rows || [];
    var pending = (data.pending || 0) + (data.pendingSessions || 0);
    var box = $("pendingPill");
    box.textContent = pending === 0
      ? "当前没有待审核项"
      : (data.pending || 0) + " 项配置待审 / " + (data.pendingSessions || 0) + " 条会话待复核";
    box.classList.toggle("is-clear", pending === 0);
    $("cards").innerHTML = rows.map(card).join("");
  }

  function card(r) {
    var review = r.review;
    var changes = (r.changes || []).length
      ? '<details class="changes"><summary>与已审核 v' + esc(review.version) + " 的差异（"
        + r.changes.length + "）</summary><ul>"
        + r.changes.map(function (c) { return "<li>" + esc(c) + "</li>"; }).join("")
        + "</ul></details>"
      : "";
    var who = review
      ? esc(review.reviewer || "—")
        + (review.operatorId ? " · " + esc(review.operatorId) : "")
        + " · " + esc(date(review.reviewedAt))
        + " · 审的是 v" + esc(review.version)
        + (review.reported ? " · 已上报" : "")
      : "还没有人登记过审核。";
    var checks = review && review.checks && review.checks.length
      ? '<div class="note">清单：' + review.checks.map(function (c) { return esc(c); }).join("、") + "</div>"
      : "";
    return '<article class="card">'
      + "<h3>" + esc(r.label) + "</h3>"
      + '<div class="num">当前 v' + esc(r.currentVersion) + " · " + esc(r.items) + " 项</div>"
      + pill(r.state)
      + '<div class="who">' + who + "</div>"
      + (review && review.note ? '<div class="note">' + esc(review.note) + "</div>" : "")
      + checks
      + changes
      + '<div class="ops"><button type="button" data-history-kind="' + esc(r.kind)
        + '" data-history-target="' + esc(r.target || "") + '">查看历史（' + esc(r.historyCount) + " 次）</button></div>"
      + "</article>";
  }

  function load() {
    return getJson(API).then(render).catch(function (err) {
      $("cards").innerHTML = '<p class="foot">' + esc(err.message) + "</p>";
    });
  }

  function loadHistory() {
    return getJson(API + "/history").then(function (data) {
      history = data || [];
    }).catch(function () { history = []; });
  }

  /* ---------------- 身份与清单 ---------------- */

  function loadIdentity() {
    getJson("/api/operator/me").then(function (data) {
      me = data && data.user;
      if (!me) {
        $("identityHint").textContent = "当前没有登录操作者；署名按手填内容记录。";
        return;
      }
      $("reviewer").value = me.name || me.id || "";
      $("reviewer").readOnly = true;
      $("identityHint").textContent = "署名来自登录身份：" + (me.name || me.id)
        + (me.role ? "（" + me.role + "）" : "") + "；组织模式下不接受手填姓名。";
    }).catch(function () {
      $("identityHint").textContent = "当前没有登录操作者；署名按手填内容记录。";
    });
  }

  function checkedItems() {
    return Array.prototype.map.call(
      document.querySelectorAll("#checks input:checked"), function (el) { return el.value; });
  }

  function updateCheckCount() {
    $("checkCount").textContent = "已勾选 " + checkedItems().length + " / 9";
  }

  document.querySelectorAll("#checks input").forEach(function (el) {
    el.addEventListener("change", updateCheckCount);
  });
  $("checkAll").onclick = function () {
    document.querySelectorAll("#checks input").forEach(function (el) { el.checked = true; });
    updateCheckCount();
  };
  $("checkNone").onclick = function () {
    document.querySelectorAll("#checks input").forEach(function (el) { el.checked = false; });
    updateCheckCount();
  };

  /* ---------------- 登记配置审核 ---------------- */

  function showMsg(text, ok) {
    var box = $("msg");
    box.textContent = text;
    box.className = "msg on " + (ok ? "ok" : "bad");
  }

  $("reviewForm").onsubmit = function (e) {
    e.preventDefault();
    var kind = $("kind").value;
    var target = kind === "flow"
      ? (rows.filter(function (r) { return r.kind === "flow"; })[0] || {}).target || ""
      : "";
    var status = document.querySelector('input[name="status"]:checked').value;
    var payload = {
      kind: kind,
      target: target,
      status: status,
      reviewer: $("reviewer").value,
      note: $("note").value,
      checks: checkedItems()
    };
    $("saveBtn").disabled = true;
    postJson(API, "PUT", payload).then(function (data) {
      render(data);
      loadHistory();
      showMsg("已登记「" + KIND[kind] + "」v" + (rows.filter(function (r) { return r.kind === kind; })[0] || {}).currentVersion
        + " 的审核结论。", true);
      $("note").value = "";
    }).catch(function (err) {
      showMsg(err.message, false);
    }).then(function () { $("saveBtn").disabled = false; });
  };

  /* ---------------- 历史弹窗 ---------------- */

  function openHistory(kind, target) {
    var entries = history.filter(function (r) {
      return r.kind === kind && (r.target || "") === (target || "");
    });
    $("historyTitle").textContent = (KIND[kind] || kind) + " 审核历史";
    $("historyBody").innerHTML = entries.length
      ? entries.map(function (r) {
          return '<div class="hist-entry">'
            + '<div class="meta">v' + esc(r.version) + " · " + esc(STATE[r.status] || r.status)
            + " · " + esc(date(r.reviewedAt)) + " · " + esc(r.reviewer || "—")
            + (r.operatorId ? " · " + esc(r.operatorId) : "")
            + (r.reported ? " · 已上报" : "") + "</div>"
            + (r.checks && r.checks.length ? "<div>清单：" + r.checks.map(esc).join("、") + "</div>" : "")
            + (r.note ? "<div>" + esc(r.note) + "</div>" : "")
            + "</div>";
        }).join("")
      : '<p class="foot">这个对象还没有历史记录。</p>';
    $("historyModal").hidden = false;
  }

  function closeHistory() {
    $("historyModal").hidden = true;
  }
  $("historyClose").onclick = closeHistory;
  $("historyModal").addEventListener("click", function (e) {
    if (e.target === $("historyModal")) closeHistory();
  });

  /* ---------------- 导出 ---------------- */

  function download(name, text, type) {
    var blob = new Blob([text], { type: type });
    var a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = name;
    document.body.appendChild(a);
    a.click();
    setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 500);
  }

  $("exportJson").onclick = function () {
    loadHistory().then(function () {
      download("socratic-mind-reviews-" + new Date().toISOString().slice(0, 10) + ".json",
        JSON.stringify(history, null, 2), "application/json;charset=utf-8");
    });
  };

  $("exportCsv").onclick = function () {
    loadHistory().then(function () {
      var head = ["kind", "target", "version", "status", "reviewer", "operatorId", "reviewedAt", "reported", "checks", "note"];
      var lines = [head.join(",")];
      history.forEach(function (r) {
        lines.push([r.kind, r.target, r.version, r.status, r.reviewer, r.operatorId, r.reviewedAt,
          r.reported ? "yes" : "no", (r.checks || []).join("|"), r.note].map(function (v) {
          return '"' + String(v == null ? "" : v).replace(/"/g, '""') + '"';
        }).join(","));
      });
      download("socratic-mind-reviews-" + new Date().toISOString().slice(0, 10) + ".csv",
        "\ufeff" + lines.join("\r\n"), "text/csv;charset=utf-8");
    });
  };

  /* ---------------- 会话心理复核 ---------------- */

  function loadSessions() {
    return getJson(API + "/sessions").then(function (data) {
      sessions = data || [];
      renderSessions();
    }).catch(function (err) {
      $("sessionRows").innerHTML = '<p class="foot">' + esc(err.message) + "</p>";
    });
  }

  function renderSessions() {
    if (!sessions.length) {
      $("sessionRows").innerHTML = '<p class="foot">当前没有触发安全关注/暂停的会话。</p>';
      return;
    }
    var body = sessions.map(function (s) {
      return '<tr data-session="' + esc(s.sessionId) + '"'
        + (selectedSession && selectedSession.sessionId === s.sessionId ? ' class="is-on"' : "") + ">"
        + '<td class="mono">' + esc(s.sessionId) + "</td>"
        + "<td>" + esc(s.stage) + "</td>"
        + "<td>" + pill(s.safetyStatus || "normal", s.safetyStatus || "normal") + "</td>"
        + "<td>" + esc(s.targetProblem || "—") + "</td>"
        + '<td class="mono">' + esc(s.turns) + " 轮"
        + (s.moodLast == null ? "" : " · 情绪 " + esc(s.moodLast)) + "</td>"
        + '<td class="mono">' + esc(date(s.updatedAt)) + "</td>"
        + "<td>" + (s.review ? pill(s.review.status) : pill("unreviewed", "未复核"))
        + ' <button class="link" type="button" data-open-session="' + esc(s.sessionId) + '">看对话</button></td>'
        + "</tr>";
    }).join("");
    $("sessionRows").innerHTML = '<table class="session-table"><thead><tr>'
      + "<th>会话</th><th>阶段</th><th>安全状态</th><th>目标问题</th><th>轮次</th><th>最近更新</th><th>复核</th>"
      + "</tr></thead><tbody>" + body + "</tbody></table>";
  }

  function selectSession(id) {
    selectedSession = sessions.filter(function (s) { return s.sessionId === id; })[0] || null;
    if (!selectedSession) return;
    renderSessions();
    getJson("/api/internal/sessions/" + encodeURIComponent(id)).then(function (trace) {
      var s = trace.session || {};
      $("sessionTitle").textContent = id + " · " + (s.stageLabel || s.stage || "")
        + (s.safetyStatus ? " · 安全状态 " + s.safetyStatus : "");
      var head = [];
      if (s.targetProblem) head.push("目标问题：" + s.targetProblem);
      if (s.automaticThought) head.push("自动思维：" + s.automaticThought);
      if (s.candidateThought) head.push("候选说法：" + s.candidateThought);
      if (s.moodSeries && s.moodSeries.length) head.push("情绪分：" + s.moodSeries.join(" → "));
      if (s.contactName) head.push("紧急联系人：" + s.contactName + (s.contactPhone ? " · " + s.contactPhone : ""));
      var msgs = (trace.messages || []).map(function (m) {
        return '<div class="msg-row ' + (m.role === "user" ? "user" : "assistant") + '">'
          + '<div class="who">' + (m.role === "user" ? "用户" : "系统")
          + (m.stage ? " · " + esc(m.stage) : "") + " · " + esc(date(m.at)) + "</div>"
          + '<div class="bubble">' + esc(m.content) + "</div></div>";
      }).join("");
      $("transcript").innerHTML = (head.length ? '<p class="foot" style="margin:0 0 10px">'
        + head.map(esc).join("<br>") + "</p>" : "") + (msgs || '<p class="foot">没有消息。</p>');

      var r = selectedSession.review;
      $("sessionStatus").value = r && SESSION_STATUS[r.status] ? r.status : "watch";
      $("sessionNote").value = r ? (r.note || "") : "";
      $("sessionReported").checked = !!(r && r.reported);
      $("sessionHistory").innerHTML = r
        ? '<p class="foot">最近一次：' + esc(STATE[r.status] || r.status) + " · " + esc(r.reviewer || "—")
          + " · " + esc(date(r.reviewedAt)) + " · 共 " + esc(selectedSession.historyCount) + " 次</p>"
        : '<p class="foot">这条会话还没有复核记录。</p>';
      $("sessionDetail").hidden = false;
      $("sessionDetail").scrollIntoView({ behavior: "smooth", block: "nearest" });
    }).catch(function (err) {
      $("sessionRows").innerHTML = '<p class="foot">' + esc(err.message) + "</p>";
    });
  }

  document.addEventListener("click", function (e) {
    var open = e.target.closest("[data-open-session]");
    if (open) {
      selectSession(open.dataset.openSession);
      return;
    }
    var hist = e.target.closest("[data-history-kind]");
    if (hist) {
      openHistory(hist.dataset.historyKind, hist.dataset.historyTarget || "");
    }
  });

  $("sessionForm").onsubmit = function (e) {
    e.preventDefault();
    if (!selectedSession) return;
    var payload = {
      kind: "session",
      target: selectedSession.sessionId,
      status: $("sessionStatus").value,
      reviewer: me ? (me.name || me.id) : $("reviewer").value,
      note: $("sessionNote").value,
      checks: ["conversation_review"],
      reported: $("sessionReported").checked
    };
    $("sessionSave").disabled = true;
    postJson(API, "PUT", payload).then(function () {
      $("sessionMsg").textContent = "已登记复核。";
      $("sessionMsg").className = "msg on ok";
      $("sessionMsg").hidden = false;
      load(); loadHistory(); loadSessions().then(function () { selectSession(payload.target); });
    }).catch(function (err) {
      $("sessionMsg").textContent = err.message;
      $("sessionMsg").className = "msg on bad";
      $("sessionMsg").hidden = false;
    }).then(function () { $("sessionSave").disabled = false; });
  };

  /* ---------------- 知识库文档审核（保持不变） ---------------- */

  var DECISION = { approve: "通过", reject: "退回", review: "转待审" };

  function loadKnowledge() {
    getJson("/api/internal/knowledge-reviews").then(function (data) {
      var rows = data || [];
      if (!rows.length) {
        $("kbReviews").innerHTML = '<p class="foot">还没有任何文档审核记录。发布前应当有人签过这一版。</p>';
        return;
      }
      var body = rows.map(function (r) {
        return "<tr>"
          + "<td>" + esc(r.title || r.documentId) + '<div class="foot" style="margin:0">'
          + esc(r.version) + " · " + esc(r.documentId) + "</div></td>"
          + "<td>" + esc(DECISION[r.decision] || r.decision) + "</td>"
          + "<td" + (r.reviewer ? "" : ' class="unsigned"') + ">"
          + esc(r.reviewer || "未署名") + "</td>"
          + "<td>" + esc(date(r.reviewedAt)) + "</td>"
          + "<td>" + esc(r.aclScope || "所有人可见") + "</td>"
          + "<td>" + esc(r.note || "—") + "</td>"
          + "</tr>";
      }).join("");
      $("kbReviews").innerHTML = '<table class="kb-rev"><thead><tr>'
        + "<th>文档</th><th>结论</th><th>审核人</th><th>时间</th><th>签字时可见范围</th><th>备注</th>"
        + "</tr></thead><tbody>" + body + "</tbody></table>";
    }).catch(function (err) {
      $("kbReviews").innerHTML = '<p class="foot">' + esc(err.message) + "</p>";
    });
  }

  $("refreshBtn").onclick = function () {
    load(); loadHistory(); loadSessions(); loadKnowledge();
  };
  document.addEventListener("keydown", function (e) {
    if (e.key === "Escape") closeHistory();
  });

  updateCheckCount();
  loadIdentity();
  load();
  loadHistory();
  loadSessions();
  loadKnowledge();
})();
