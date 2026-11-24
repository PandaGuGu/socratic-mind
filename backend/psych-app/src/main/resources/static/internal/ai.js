/**
 * 模型仪表盘：只读最近调用，不做开关。
 *
 * 数字的意义有限定：延迟是实测，token 是估算，成本只对本地模型为 0。
 * 页面上必须保留这句话，否则近似值会被当成账单。
 */
(function () {
  var API = "/api/internal/ai";
  var $ = function (id) { return document.getElementById(id); };

  function esc(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  function clock(v) {
    if (!v) return "—";
    var d = new Date(v);
    if (isNaN(d.getTime())) return String(v);
    var p = function (n) { return (n < 10 ? "0" : "") + n; };
    return p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds());
  }

  function renderMeta(d) {
    $("meta").innerHTML = "<span>provider " + esc(d.provider) + "</span>"
      + "<span>chat " + esc(d.chatModel || "—") + "</span>"
      + "<span>embedding " + esc(d.embeddingModel) + " · " + esc(d.dimension) + " 维</span>"
      + "<span>" + (d.stub ? "当前是桩" : "当前接真实模型") + "</span>";
    $("costNote").textContent = d.costNote || "";
  }

  function renderRows(rows) {
    if (!rows.length) {
      $("rows").innerHTML = '<tr><td colspan="6" style="padding:28px;text-align:center;color:var(--muted)">'
        + "还没有调用。去房间说一句，或跑一次知识问答。</td></tr>";
      return;
    }
    $("rows").innerHTML = rows.map(function (c) {
      var bad = c.outcome !== "ok";
      return "<tr><td class=\"num\">" + esc(clock(c.at)) + "</td><td>" + esc(c.kind)
        + "</td><td>" + esc(c.model) + "</td><td class=\"num\">" + esc(c.ms)
        + " ms</td><td class=\"num\">" + esc(c.estTokens)
        + "</td><td class=\"" + (bad ? "bad" : "ok") + "\">" + esc(c.outcome) + "</td></tr>";
    }).join("");
  }

  function load() {
    fetch(API, { headers: { "Accept": "application/json" } })
      .then(function (res) {
        if (!res.ok) throw new Error("读取失败（" + res.status + "）");
        return res.json();
      })
      .then(function (d) {
        var s = d.summary || {};
        $("stCalls").textContent = String(s.calls || 0);
        $("stP50").textContent = (s.p50ms || 0) + " ms";
        $("stP95").textContent = (s.p95ms || 0) + " ms";
        $("stTokens").textContent = String(s.estTokens || 0);
        $("stErrors").textContent = String(s.errors || 0);
        $("stErrors").className = (s.errors || 0) > 0 ? "bad" : "";
        renderMeta(d);
        renderRows(d.recent || []);
      })
      .catch(function (err) {
        $("rows").innerHTML = '<tr><td colspan="6" style="padding:28px;text-align:center;color:var(--muted)">'
          + esc(err.message) + "</td></tr>";
      });
  }

  $("refreshBtn").onclick = load;
  load();
})();
