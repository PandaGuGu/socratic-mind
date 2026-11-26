/**
 * 临床审核记录页。
 *
 * 页面不判断"能不能上线"，只做两件事：把当前状态摆出来、把签字内容记下来。
 * 默认未审核；改版后自动 stale。这是"未经临床审核"这句话唯一能落地的形态。
 */
(function () {
  var API = "/api/internal/reviews";
  var $ = function (id) { return document.getElementById(id); };

  var STATE = {
    unreviewed: "未审核",
    stale: "版本已变，需重审",
    approved: "已通过",
    changes_requested: "需修改"
  };

  var KIND = { safety: "安全规则", lexicon: "敏感词库" };

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

  function render(data) {
    $("cards").innerHTML = (data.rows || []).map(function (r) {
      var review = r.review;
      return '<article class="card">'
        + "<h3>" + esc(r.label) + "</h3>"
        + '<div class="num">当前 v' + esc(r.currentVersion) + " · " + esc(r.items) + " 项</div>"
        + '<span class="state ' + esc(r.state) + '">' + esc(STATE[r.state] || r.state) + "</span>"
        + (review
          ? '<div class="who">' + esc(review.reviewer || "—") + " · " + esc(date(review.reviewedAt))
            + " · 审的是 v" + esc(review.version) + "</div>"
            + (review.note ? '<div class="note">' + esc(review.note) + "</div>" : "")
          : '<div class="who">还没有人登记过审核。</div>')
        + "</article>";
    }).join("");
  }

  function load() {
    fetch(API, { headers: { "Accept": "application/json" } })
      .then(function (res) {
        if (!res.ok) throw new Error("读取失败（" + res.status + "）");
        return res.json();
      })
      .then(render)
      .catch(function (err) {
        $("cards").innerHTML = '<p class="foot">' + esc(err.message) + "</p>";
      });
  }

  function show(text, ok) {
    var box = $("msg");
    box.textContent = text;
    box.className = "msg on " + (ok ? "ok" : "bad");
    box.hidden = false;
  }

  $("reviewForm").onsubmit = function (e) {
    e.preventDefault();
    var status = document.querySelector('input[name="status"]:checked').value;
    $("saveBtn").disabled = true;
    fetch(API, {
      method: "PUT",
      headers: { "Content-Type": "application/json", "Accept": "application/json" },
      body: JSON.stringify({
        kind: $("kind").value,
        status: status,
        reviewer: $("reviewer").value,
        note: $("note").value
      })
    }).then(function (res) {
      return res.json().catch(function () { return {}; }).then(function (data) {
        if (!res.ok) throw new Error(data.message || "登记失败");
        render(data);
        show("已登记「" + KIND[$("kind").value] + "」当前版本的审核结论。", true);
        $("note").value = "";
      });
    }).catch(function (err) {
      show(err.message, false);
    }).then(function () { $("saveBtn").disabled = false; });
  };

  $("refreshBtn").onclick = load;
  load();
  loadKnowledge();

  var DECISION = { approve: "通过", reject: "退回", review: "转待审" };

  /**
   * 知识库文档的审核记录。
   *
   * 与上面两张卡片分开渲染：规则/词库只有"当前结论"，文档是"一版一条历史"。
   * 把两种读法塞进同一个卡片列表，会让人以为文档审核也只有一条。
   */
  function loadKnowledge() {
    fetch("/api/internal/knowledge-reviews", { headers: { Accept: "application/json" } })
      .then(function (res) {
        if (!res.ok) throw new Error("读取失败（" + res.status + "）");
        return res.json();
      })
      .then(function (rows) {
        if (!rows.length) {
          $("kbReviews").innerHTML = '<p class="foot">还没有任何文档审核记录。发布前应当有人签过这一版。</p>';
          return;
        }
        var body = rows.map(function (r) {
          return "<tr>"
            + "<td>" + esc(r.title || r.documentId) + "<div class=\"foot\" style=\"margin:0\">"
              + esc(r.version) + " · " + esc(r.documentId) + "</div></td>"
            + "<td>" + esc(DECISION[r.decision] || r.decision) + "</td>"
            + "<td" + (r.reviewer ? "" : ' class="unsigned"') + ">"
              + esc(r.reviewer || "未署名") + "</td>"
            + "<td>" + esc(date(r.reviewedAt)) + "</td>"
            + "<td>" + esc(r.aclScope || "所有人可见") + "</td>"
            + "<td>" + esc(r.note || "—") + "</td>"
            + "</tr>";
        }).join("");
        $("kbReviews").innerHTML = "<table class=\"kb-rev\"><thead><tr>"
          + "<th>文档</th><th>结论</th><th>审核人</th><th>时间</th><th>签字时可见范围</th><th>备注</th>"
          + "</tr></thead><tbody>" + body + "</tbody></table>";
      })
      .catch(function (err) {
        $("kbReviews").innerHTML = '<p class="foot">' + esc(err.message) + "</p>";
      });
  }
})();
