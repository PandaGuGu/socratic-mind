/**
 * 记录页：把一条会话渲染成"可以打印、可以交给咨询师"的文档。
 *
 * 三条克制：
 * 1. 只投影已有数据，不补全、不润色、不下结论；
 * 2. 安全规则、偏差候选都带着"这不是诊断"的说明一起出现；
 * 3. 打印样式不依赖浏览器"简化页面"，正文本身就没有交互控件。
 */
(function () {
  var id = new URLSearchParams(location.search).get("id");
  var doc = document.getElementById("doc");
  var state = document.getElementById("docState");

  var STAGE = {
    PROBLEM_IDENTIFICATION: "情境",
    COGNITIVE_EXTRACTION: "想法",
    COGNITIVE_REVIEW: "检验",
    INTERVENTION_DECISION: "行动",
    ACTION_CONFIRM: "确认",
    ACTION_REVIEW: "复盘",
    SESSION_SUMMARY: "总结",
    SAFETY_HOLD: "安全暂停",
    ENDED: "结束"
  };

  var INTERVENTION = {
    behavioral_experiment: "行为实验",
    cognitive_restructuring: "认知重构",
    self_monitoring: "自我观察",
    professional_support: "专业支持"
  };

  var ACTION = {
    start: "开始",
    answer: "回答",
    blocked: "被拦住",
    safety_hold: "安全暂停",
    confirm: "用户确认",
    end: "结束"
  };

  function esc(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function when(iso) {
    if (!iso) return "—";
    var d = new Date(iso);
    if (isNaN(d.getTime())) return String(iso);
    var p = function (n) { return (n < 10 ? "0" : "") + n; };
    return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate())
      + " " + p(d.getHours()) + ":" + p(d.getMinutes());
  }

  function stageName(v) {
    return STAGE[v] || v || "—";
  }

  var SAFETY = {
    normal: "正常",
    watch: "关注（自评偏低）",
    review: "待人工复核",
    hold: "安全暂停"
  };

  function statusName(session) {
    if (session.stage === "SAFETY_HOLD") return "安全暂停 · 已转介";
    if (session.status === "ended") return "已结束";
    return session.awaitingUser ? "停在确认，等用户" : "进行中";
  }

  function field(label, value, key) {
    var empty = value == null || String(value).trim() === "";
    return '<li class="' + (key ? "is-key" : "") + '"><dt>' + esc(label) + "</dt><dd"
      + (empty ? ' class="empty"' : "") + ">"
      + (empty ? "未填写" : esc(value)) + "</dd></li>";
  }

  function render(data) {
    var s = data.session || {};
    var messages = data.messages || [];
    var trace = data.trace || [];
    var moods = Array.isArray(s.moodSeries) ? s.moodSeries : [];
    var distortions = Array.isArray(s.distortions) ? s.distortions : [];

    var html = "";

    html += '<p class="kicker">SOCRATIC MIND · 记录页</p>';
    html += "<h1>一次 CBT 自助练习的记录</h1>";
    html += '<div class="meta">'
      + "<span>生成 " + esc(when(new Date().toISOString())) + "</span>"
      + "<span>会话 " + esc(String(s.sessionId || "").slice(0, 22)) + "</span>"
      + "<span>" + esc(statusName(s)) + "</span>"
      + "</div>";

    html += '<section class="block"><h2>概要</h2><dl class="facts">'
      + fact("开始", when(s.createdAt))
      + fact("最近一次", when(s.updatedAt))
      + fact("走到第几步", (Number(s.visibleIndex || 0) + 1) + " / 5 · " + esc(stageName(s.stage)))
      + fact("轮次", (s.turnCount || 0) + " 轮")
      + fact("自评次数", moods.length ? moods.length + " 次" : "没有自评")
      + fact("安全状态", SAFETY[s.safetyStatus] || s.safetyStatus || "正常")
      + "</dl></section>";

    html += '<section class="block"><h2>这一轮写了什么</h2><dl class="chain">'
      + field("目标问题", s.targetProblem)
      + field("自动想法", s.automaticThought)
      + field("系统提议的说法", s.candidateThought)
      + field("用户确认的说法", s.userConfirmedThought, true)
      + field("干预方式", INTERVENTION[s.interventionType] || s.interventionType)
      + field("行动计划", s.actionPlan)
      + field("后来发生了什么", s.actionResult)
      + field("本轮总结", s.summary)
      + "</dl></section>";

    if (moods.length) {
      html += '<section class="block"><h2>情绪自评</h2><div class="moods">';
      moods.forEach(function (score, i) {
        html += '<span class="' + (score <= 3 ? "low" : "") + '">第 ' + (i + 1) + " 次 · " + esc(score) + " 分</span>";
      });
      html += '</div><p class="note">分数全部由用户自己点击，系统不推断、不插值；'
        + "单次低分不等于危机，也不作为任何判断依据。</p></section>";
    }

    if (distortions.length) {
      html += '<section class="block"><h2>自动思维里的候选偏差</h2><div class="tags">';
      distortions.forEach(function (d) { html += "<span>" + esc(d) + "</span>"; });
      html += '</div><p class="note">这些是规则标出的候选，不是诊断，也没有写进总结。'
        + "它们只用来提示“从哪个方向找不一样的情况”。</p></section>";
    }

    if (["hold", "watch", "review"].includes(s.safetyStatus) || s.stage === "SAFETY_HOLD") {
      html += '<section class="block"><h2>安全事件</h2>';
      html += '<div class="safety"><h3>'
        + (s.stage === "SAFETY_HOLD" ? "命中安全规则，流程已暂停并转介" : "被标记为需要关注")
        + "</h3>";
      if (s.safetyStatus === "watch") {
        html += "<p>自评偏低触发的\"关注\"，不触发安全暂停，也不构成危机判断。</p>";
      }
      if (s.safetyReason) html += "<p>记录的原因：" + esc(s.safetyReason) + "</p>";
      if (s.contactName || s.contactPhone) {
        html += "<p>紧急联系人（用户自填）：" + esc(s.contactName || "未填称呼")
          + (s.contactRelation ? "（" + esc(s.contactRelation) + "）" : "")
          + (s.contactPhone ? " · " + esc(s.contactPhone) : "") + "</p>";
      } else {
        html += "<p>用户没有留下紧急联系人。</p>";
      }
      html += "<p>后台处置：" + (s.safetyAckAt ? "已标记处理于 " + esc(when(s.safetyAckAt)) : "尚未标记") + "</p>";
      html += "<p>热线：12356（全国统一心理援助热线） · 120（急救）</p>";
      html += "</div></section>";
    }

    html += '<section class="block"><h2>过程</h2><div class="messages">';
    if (!messages.length) {
      html += '<p class="note">这一轮还没有消息。</p>';
    } else {
      var lastStage = null;
      messages.forEach(function (m) {
        if (!m.content) return;
        if (m.stage !== lastStage) {
          html += '<div class="stage">' + esc(stageName(m.stage)) + "</div>";
          lastStage = m.stage;
        }
        var isUser = m.role === "user";
        html += '<div class="msg ' + (isUser ? "is-user" : "is-bot") + '">'
          + '<span class="who">' + (isUser ? "我" : "它") + "</span>"
          + '<div class="text">' + esc(m.content) + "</div></div>";
      });
    }
    html += "</div></section>";

    if (trace.length) {
      html += '<section class="block"><h2>附：系统轨迹（供复核）</h2>'
        + '<table class="trace"><thead><tr>'
        + "<th>#</th><th>动作</th><th>阶段</th><th>耗时</th><th>模型</th><th>拦截 / 规则</th>"
        + "</tr></thead><tbody>";
      trace.forEach(function (t) {
        var path = (t.stageFrom ? stageName(t.stageFrom) : "—") + " → " + (t.stageTo ? stageName(t.stageTo) : "—");
        html += "<tr><td>" + esc(t.turnNo) + "</td><td>" + esc(ACTION[t.action] || t.action)
          + "</td><td>" + esc(path) + "</td><td>" + esc(t.latencyMs) + " ms</td><td>"
          + esc(t.modelMode || "—") + "</td><td>"
          + esc(t.safetyRule || t.guardRule || "—") + "</td></tr>";
      });
      html += "</tbody></table></section>";
    }

    html += '<footer class="foot">'
      + "<p><b>这份记录是什么：</b>系统按会话数据直接生成，未经人工编辑；"
      + "它记录的是用户自己写下的内容与流程走到哪一步，不是临床评估。</p>"
      + "<p><b>它不是什么：</b>不是诊断、不是病历，也不能替代面谈；偏差标签与安全规则来自工程常识，"
      + "尚未经过临床审核。情绪分由用户自己点，系统没有推断。</p>"
      + "<p><b>交给谁之前：</b>这里含心理内容。只交给用户同意交付的人；"
      + "打印或导出后，这份纸不再受系统的访问控制保护。</p>"
      + "</footer>";

    doc.innerHTML = html;
    state.textContent = "已生成 · " + when(new Date().toISOString());
    document.title = "记录 · " + (s.targetProblem || "").slice(0, 12) + " · Socratic Mind";
  }

  function fact(label, value) {
    return "<div><dt>" + esc(label) + "</dt><dd>" + esc(value) + "</dd></div>";
  }

  document.getElementById("printBtn").onclick = function () { window.print(); };

  if (!id) {
    doc.innerHTML = '<div class="failed">地址里缺少 id。请从控制台选中一条会话，再点"记录页"。</div>';
    state.textContent = "缺少会话 id";
    return;
  }

  fetch("/api/internal/sessions/" + encodeURIComponent(id), {
    headers: { "Accept": "application/json" }
  }).then(function (res) {
    if (!res.ok) throw new Error(res.status === 404 ? "没有这条会话" : "读取失败（" + res.status + "）");
    return res.json();
  }).then(render).catch(function (err) {
    doc.innerHTML = '<div class="failed">' + esc(err.message)
      + '<br><br><a href="./index.html">回控制台</a></div>';
    state.textContent = "没有取到记录";
  });
})();
