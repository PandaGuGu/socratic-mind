/**
 * 账号视图。只读：不提供删除、不提供禁用——那种能力应该在产品侧由本人持口令完成。
 *
 * 页面只做三件事：看总量、按邮箱找人、把"名下有待关注的会话"标出来。
 * 不做"活跃度排名"：那会诱导把使用频率当成病情指标。
 */
(function () {
  var API = "/api/internal/accounts";
  var $ = function (id) { return document.getElementById(id); };
  var data = null;

  function esc(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function date(v) {
    if (!v) return "—";
    var d = new Date(v);
    if (isNaN(d.getTime())) return "—";
    var p = function (n) { return (n < 10 ? "0" : "") + n; };
    return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate())
      + " " + p(d.getHours()) + ":" + p(d.getMinutes());
  }

  function render() {
    var q = $("search").value.trim().toLowerCase();
    var rows = (data.rows || []).filter(function (r) {
      if (!q) return true;
      return String(r.email || "").toLowerCase().indexOf(q) >= 0
        || String(r.displayName || "").toLowerCase().indexOf(q) >= 0;
    });

    $("total").textContent = String(data.total || 0);
    $("active").textContent = String(data.active7d || 0);
    $("empty").textContent = String(data.withoutSessions || 0);

    if (!rows.length) {
      $("rows").innerHTML = '<tr><td class="empty-row" colspan="7">'
        + (q ? "没有匹配的账号。" : "还没有人注册。不注册也能完整使用，这是设计，不是缺陷。")
        + "</td></tr>";
      return;
    }

    $("rows").innerHTML = rows.map(function (r) {
      return "<tr>"
        + '<td><span class="email">' + esc(r.email) + "</span></td>"
        + "<td>" + esc(r.displayName || "—") + "</td>"
        + '<td class="muted">' + esc(date(r.createdAt)) + "</td>"
        + '<td class="muted">' + esc(date(r.lastLoginAt)) + "</td>"
        + '<td class="num">' + esc(r.sessions) + "</td>"
        + '<td class="muted">' + esc(date(r.lastSessionAt)) + "</td>"
        + "<td>" + (r.attention > 0
          ? '<span class="pill-attention">' + esc(r.attention) + " 轮</span>"
          : '<span class="muted">—</span>') + "</td>"
        + "</tr>";
    }).join("");
  }

  function load() {
    fetch(API, { headers: { "Accept": "application/json" } })
      .then(function (res) {
        if (!res.ok) throw new Error("读取失败（" + res.status + "）");
        return res.json();
      })
      .then(function (json) {
        data = json;
        render();
      })
      .catch(function (err) {
        $("rows").innerHTML = '<tr><td class="empty-row" colspan="7">' + esc(err.message) + "</td></tr>";
      });
  }

  $("search").addEventListener("input", function () { if (data) render(); });
  $("refreshBtn").addEventListener("click", load);
  load();
})();
