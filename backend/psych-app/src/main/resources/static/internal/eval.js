/**
 * 在线评测页。
 *
 * 只做四件事：把窗口内的率摆出来、把待复核样本列出来、把抽样摆出来、能标记已处理。
 * 刻意不做图表：badcase 池的价值全在"有人一条条看完"，曲线会让人以为看过了。
 */
(function () {
  var API = "/api/internal/eval";
  var $ = function (id) { return document.getElementById(id); };

  var STATUS_LABEL = {
    answered: "已回答",
    insufficient_evidence: "资料不足",
    error: "管道异常"
  };

  var REASON_LABEL = {
    insufficient_evidence: "拒答",
    citation_invalid: "引用不成立",
    pipeline_error: "管道异常",
    manual: "人工提交"
  };

  function esc(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function pct(v) {
    return (Math.round((v || 0) * 1000) / 10).toFixed(1) + "%";
  }

  function date(v) {
    if (!v) return "—";
    var d = new Date(v);
    if (isNaN(d.getTime())) return String(v);
    var p = function (n) { return (n < 10 ? "0" : "") + n; };
    return p(d.getMonth() + 1) + "-" + p(d.getDate()) + " " + p(d.getHours()) + ":" + p(d.getMinutes());
  }

  function days() {
    return $("days").value || "7";
  }

  function renderStats(s) {
    var cells = [
      { label: "回答率", value: pct(s.answeredRate), sub: s.answered + " / " + s.total + " 轮" },
      { label: "拒答率", value: pct(s.refusalRate), sub: s.refused + " 轮判为资料不足" },
      { label: "引用有效率", value: pct(s.citationValidRate), sub: "分母是已回答的 " + s.answered + " 轮" },
      { label: "待处理样本", value: String(s.openBadCases == null ? "—" : s.openBadCases), sub: "P95 " + s.p95LatencyMs + " ms" }
    ];
    $("stats").innerHTML = cells.map(function (c) {
      return "<div class=\"stat\"><dt>" + esc(c.label) + "</dt><dd>" + esc(c.value) + "</dd>"
        + "<div class=\"sub\">" + esc(c.sub) + "</div></div>";
    }).join("");
  }

  function renderBadCases(rows) {
    if (!rows || !rows.length) {
      $("badcases").innerHTML = "<p class=\"empty2\">这一窗口内没有待复核样本。</p>";
      return;
    }
    var body = rows.map(function (b) {
      var resolved = b.resolved;
      return "<tr>"
        + "<td class=\"q\">" + esc(b.query) + "</td>"
        + "<td><span class=\"pill " + (b.source === "auto" ? "bad" : "") + "\">"
          + esc(REASON_LABEL[b.reason] || b.reason || "—") + "</span></td>"
        + "<td class=\"why\">" + esc(b.source) + " · " + esc(date(b.createdAt)) + "</td>"
        + "<td>" + (resolved
          ? "<span class=\"why\">已处理 " + esc(date(b.resolvedAt)) + "</span>"
          : "<button class=\"btn\" data-resolve=\"" + esc(b.id) + "\" type=\"button\">标记已处理</button>")
        + "</td></tr>";
    }).join("");
    $("badcases").innerHTML = "<table class=\"ev\"><thead><tr>"
      + "<th>问题</th><th>原因</th><th>来源 · 时间</th><th>操作</th>"
      + "</tr></thead><tbody>" + body + "</tbody></table>";

    $("badcases").querySelectorAll("[data-resolve]").forEach(function (btn) {
      btn.addEventListener("click", function () { resolve(btn.dataset.resolve, btn); });
    });
  }

  function renderSample(rows) {
    if (!rows || !rows.length) {
      $("sample").innerHTML = "<p class=\"empty2\">还没有记录。</p>";
      return;
    }
    $("sample").innerHTML = rows.map(function (o) {
      var ok = o.status === "answered";
      return "<li><span class=\"pill " + (ok ? "ok" : "bad") + "\">"
        + esc(STATUS_LABEL[o.status] || o.status) + "</span> " + esc(o.query)
        + "<div class=\"meta\">命中 " + esc(o.hits) + " · 引用覆盖 "
        + esc(Math.round((o.coverage || 0) * 100)) + "% · " + esc(o.latencyMs) + " ms</div></li>";
    }).join("");
  }

  function resolve(id, btn) {
    btn.disabled = true;
    fetch(API + "/badcases/" + encodeURIComponent(id) + "/resolve", { method: "POST" })
      .then(function (res) {
        if (!res.ok) throw new Error("标记失败（" + res.status + "）");
        return load();
      })
      .catch(function (err) {
        btn.disabled = false;
        btn.textContent = err.message;
      });
  }

  function load() {
    var d = days();
    var withResolved = $("showResolved").checked;
    var jobs = [
      fetch(API + "/summary?days=" + d).then(function (r) { return r.json(); }).then(renderStats),
      fetch(API + "/badcases?unresolved=" + (!withResolved)).then(function (r) { return r.json(); }).then(renderBadCases),
      fetch(API + "/sample?days=" + d + "&n=12").then(function (r) { return r.json(); }).then(renderSample)
    ];
    return Promise.all(jobs).catch(function (err) {
      $("badcases").innerHTML = "<p class=\"empty2\">读取失败：" + esc(err.message) + "</p>";
    });
  }

  $("refreshBtn").addEventListener("click", load);
  $("days").addEventListener("change", load);
  $("showResolved").addEventListener("change", load);
  load();
})();
