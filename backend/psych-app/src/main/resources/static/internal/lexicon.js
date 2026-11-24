/**
 * 词库后台。
 *
 * 一条规则写在这里：**编辑器里的内容不直接生效**。点"保存"走 PUT，
 * 服务端校验（结构 + 至少一条 block + 至少一条输入侧）通过才安装新表；
 * 不通过就把错误原样显示出来，旧表继续跑。
 *
 * 不在前端做"看起来很像"的校验：前端能拦住的是手滑，
 * 拦不住"把 block 删光"这类语义错误——那种判断只能有一份，放在服务端。
 */
(function () {
  var API = "/api/internal/lexicon";

  var $ = function (id) { return document.getElementById(id); };
  var editor = $("editor");
  var msg = $("msg");

  function esc(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function show(text, ok) {
    msg.textContent = text;
    msg.className = "lex-msg is-on " + (ok ? "is-ok" : "is-bad");
    msg.hidden = false;
  }

  function hide() {
    msg.className = "lex-msg";
    msg.hidden = true;
  }

  function request(path, options) {
    return fetch(API + path, options).then(function (res) {
      return res.json().catch(function () { return {}; }).then(function (data) {
        if (!res.ok) {
          throw new Error(data.message || data.error || ("请求失败 " + res.status));
        }
        return data;
      });
    });
  }

  function linesOf(entries) {
    // 文件与页面都用小写；接口回的是大写枚举，转一次，让"看到的就是文件里的"。
    return entries.map(function (e) {
      return [String(e.scope).toLowerCase(), e.category, String(e.action).toLowerCase(), e.word].join("|");
    }).join("\n");
  }

  /** 只解析非注释行；格式不对的行带着行号报出来，而不是悄悄丢掉。 */
  function parse() {
    var rows = [];
    var errors = [];
    editor.value.split(/\r?\n/).forEach(function (raw, i) {
      var line = raw.trim();
      if (!line || line.charAt(0) === "#") return;
      var parts = line.split("|");
      if (parts.length !== 4) {
        errors.push("第 " + (i + 1) + " 行不是 4 段：" + line);
        return;
      }
      rows.push({
        scope: parts[0].trim(),
        category: parts[1].trim(),
        action: parts[2].trim(),
        word: parts[3].trim()
      });
    });
    if (errors.length) throw new Error(errors.slice(0, 3).join("；"));
    return rows;
  }

  function render(view) {
    editor.value = linesOf(view.entries || []);
    $("countHint").textContent = (view.count || 0) + " 条 · " +
      (view.persisted ? "已落盘，重启仍在" : "随包基线（保存后落盘）");
    var stamp = view.updatedAt ? new Date(view.updatedAt).toLocaleString("zh-CN", { hour12: false }) : "随包基线";
    $("meta").textContent = "v" + (view.version || 0) + " · " + stamp + " · " + (view.updatedBy || "builtin");
    $("meta").title = view.path || "";

    var hist = $("history");
    hist.innerHTML = (view.history && view.history.length)
      ? view.history.map(function (h) {
          return "<li><span>v" + h.version
            + ' <button class="link" type="button" data-v="' + h.version + '">回滚</button></span>'
            + "<span>" + esc(h.updatedBy || "—") + " · " + esc(short(h.updatedAt)) + "</span></li>";
        }).join("")
      : "<li><span>还没有保存过</span></li>";
    hist.querySelectorAll("button[data-v]").forEach(function (btn) {
      btn.addEventListener("click", function () { rollback(btn.dataset.v); });
    });
  }

  function short(value) {
    if (!value) return "—";
    var d = new Date(value);
    if (isNaN(d.getTime())) return String(value);
    var p = function (n) { return (n < 10 ? "0" : "") + n; };
    return p(d.getMonth() + 1) + "-" + p(d.getDate()) + " " + p(d.getHours()) + ":" + p(d.getMinutes());
  }

  function load() {
    hide();
    return request("").then(render).catch(function (err) {
      show("读不到词库：" + err.message, false);
    });
  }

  $("saveBtn").onclick = function () {
    var rows;
    try {
      rows = parse();
    } catch (e) {
      show(e.message, false);
      return;
    }
    $("saveBtn").disabled = true;
    request("", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ entries: rows, updatedBy: "ops" })
    }).then(function (view) {
      render(view);
      show("已保存并生效：v" + view.version + "，" + view.count + " 条。下一句话就按新词库扫。", true);
    }).catch(function (err) {
      show("没有保存：" + err.message, false);
    }).then(function () { $("saveBtn").disabled = false; });
  };

  $("validateBtn").onclick = function () {
    var rows;
    try {
      rows = parse();
    } catch (e) {
      show(e.message, false);
      return;
    }
    request("/validate", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ entries: rows })
    }).then(function (data) {
      if (data.ok) show("这份草稿能保存：" + rows.length + " 条。", true);
      else show("这份草稿保存不了：" + (data.errors || []).join("；"), false);
    }).catch(function (err) { show(err.message, false); });
  };

  function rollback(version) {
    if (!window.confirm("回滚到 v" + version + "？这会生成一个新版本，历史不会被改写。")) return;
    request("/rollback", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ version: Number(version), updatedBy: "ops" })
    }).then(function (view) {
      render(view);
      show("已回滚：v" + version + " 的内容成为 v" + view.version + "，旧版本仍可查。", true);
    }).catch(function (err) { show("没有回滚：" + err.message, false); });
  }

  $("resetBtn").onclick = function () {
    if (!window.confirm("把词库恢复成随包基线？当前自定义的词会被覆盖，但历史快照仍可查。")) return;
    request("/reset", { method: "POST" }).then(function (view) {
      render(view);
      show("已恢复随包基线，作为 v" + view.version + " 记入历史。", true);
    }).catch(function (err) { show(err.message, false); });
  };

  $("testBtn").onclick = function () {
    var side = document.querySelector('input[name="side"]:checked').value;
    request("/test", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text: $("testText").value, side: side })
    }).then(function (data) {
      var hits = data.hits || [];
      $("hits").innerHTML = hits.length
        ? hits.map(function (h) {
            var cls = h.action === "BLOCK" ? "is-bad" : "";
            return '<li><b class="' + cls + '">' + esc(h.action) + "</b><span>" + esc(h.category)
              + " · " + esc(h.word) + "</span></li>";
          }).join("")
        : "<li><span>没有命中。返回空是正常结果，不是失败。</span></li>";
    }).catch(function (err) { show(err.message, false); });
  };

  $("refreshBtn").onclick = load;

  load();
})();
