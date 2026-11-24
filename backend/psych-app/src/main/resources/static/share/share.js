/**
 * 一次性分享页：凭链接看一轮记录，只此一次。
 *
 * 这里不判断"该不该给谁看"——这个决定在生成链接的人手里。页面只负责：
 * 1. 打开即消耗（服务端原子占用），刷新后显示"已用过"；
 * 2. 只显示记录本身：阶段、想法链、情绪、偏差、消息、安全状态；
 * 3. 不显示联系人、内部轨迹、可枚举的会话 id。
 */
(function () {
  var token = new URLSearchParams(location.search).get("t");
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

  function stageName(v) { return STAGE[v] || v || "—"; }

  function field(label, value, key) {
    var empty = value == null || String(value).trim() === "";
    return '<li class="' + (key ? "is-key" : "") + '"><dt>' + esc(label) + "</dt><dd"
      + (empty ? ' class="empty"' : "") + ">" + (empty ? "未填写" : esc(value)) + "</dd></li>";
  }

  function render(data) {
    var s = data.session || {};
    var messages = data.messages || [];
    var moods = Array.isArray(s.moodSeries) ? s.moodSeries : [];
    var distortions = Array.isArray(s.distortions) ? s.distortions : [];

    var html = "";
    html += '<p class="kicker">SOCRATIC MIND · 一次性记录</p>';
    html += "<h1>一次 CBT 自助练习的记录</h1>";
    html += '<div class="meta">'
      + "<span>打开于 " + esc(when(data.sharedAt)) + "</span>"
      + "<span>链接到期 " + esc(when(data.expiresAt)) + "</span>"
      + "<span>已使用 · 刷新后失效</span>"
      + "</div>";

    html += '<section class="block"><h2>概要</h2><dl class="facts">'
      + fact("开始", when(s.createdAt))
      + fact("最近一次", when(s.updatedAt))
      + fact("走到", stageName(s.stage))
      + fact("安全状态", s.safetyStatus === "normal" || !s.safetyStatus ? "正常" : esc(s.safetyStatus))
      + "</dl></section>";

    html += '<section class="block"><h2>这一轮写了什么</h2><dl class="chain">'
      + field("目标问题", s.targetProblem)
      + field("自动想法", s.automaticThought)
      + field("系统提议的说法", s.candidateThought)
      + field("本人确认的说法", s.userConfirmedThought, true)
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
      html += '</div><p class="note">分数由本人点击，系统不推断、不插值；单次低分不等于危机。</p></section>';
    }

    if (distortions.length) {
      html += '<section class="block"><h2>自动思维里的候选偏差</h2><div class="tags">';
      distortions.forEach(function (d) { html += "<span>" + esc(d) + "</span>"; });
      html += '</div><p class="note">这些是规则标出的候选，不是诊断，也没有写进总结。</p></section>';
    }

    if (["hold", "watch", "review"].includes(s.safetyStatus) || s.stage === "SAFETY_HOLD") {
      html += '<section class="block"><h2>安全事件</h2><div class="safety">'
        + "<h3>" + (s.stage === "SAFETY_HOLD" ? "命中安全规则，流程已暂停并转介" : "被标记为需要关注") + "</h3>"
        + "<p>紧急时：12356（全国统一心理援助热线） · 120（急救）</p>"
        + "</div></section>";
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
          + '<span class="who">' + (isUser ? "本人" : "系统") + "</span>"
          + '<div class="text">' + esc(m.content) + "</div></div>";
      });
    }
    html += "</div></section>";

    html += '<footer class="foot">'
      + "<p><b>这份记录是什么：</b>由本人从 Socratic Mind 主动分享，链接只能打开一次，到期或撤回后失效。</p>"
      + "<p><b>它不是什么：</b>不是诊断、不是病历，也不能替代面谈；安全规则与偏差标签来自工程常识，尚未经过临床审核。</p>"
      + "<p><b>关于这份纸：</b>打印或另存后，它不再受系统访问控制保护。请只交给本人同意交付的人。</p>"
      + "</footer>";

    doc.innerHTML = html;
    state.textContent = "已打开 · 链接已使用";
    document.title = "一次 CBT 自助练习的记录";
  }

  function fact(label, value) {
    return "<div><dt>" + esc(label) + "</dt><dd>" + esc(value) + "</dd></div>";
  }

  document.getElementById("printBtn").onclick = function () { window.print(); };

  if (!token) {
    doc.innerHTML = '<div class="failed">地址里缺少链接参数。请向分享者要完整链接。</div>';
    state.textContent = "缺少链接";
    return;
  }

  fetch("/api/share/" + encodeURIComponent(token), { headers: { "Accept": "application/json" } })
    .then(function (res) {
      return res.json().catch(function () { return {}; }).then(function (data) {
        if (!res.ok) throw new Error(data.message || "这条链接已经用过、过期或被撤回了");
        return data;
      });
    })
    .then(render)
    .catch(function (err) {
      doc.innerHTML = '<div class="failed">' + esc(err.message)
        + "<br><br>一次性链接只能打开一次；需要重看，请让分享者重新生成。</div>";
      state.textContent = "链接不可用";
    });
})();
