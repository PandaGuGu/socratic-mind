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

  /* ---------------- 模型接入配置 ---------------- */

  var CFG = "/api/internal/ai/config";
  var LOCAL_API = "/api/internal/ai/local";
  var LOCAL_BASE = "http://localhost:11434/v1";

  function say(text, kind) {
    var box = $("cfgResult");
    box.textContent = text;
    box.className = "cfg-result is-on " + (kind || "is-info");
  }

  function mode() {
    var picked = document.querySelector('input[name="aiMode"]:checked');
    return picked ? picked.value : "stub";
  }

  function fill(c) {
    if (!c) return;
    var radio = document.querySelector('input[name="aiMode"][value="' + c.mode + '"]');
    if (radio) radio.checked = true;
    $("cfgBase").value = c.baseUrl || "";
    $("cfgModel").value = c.chatModel || "";
    $("cfgKey").value = "";
    $("cfgKey").placeholder = c.apiKeySet ? "已保存（不显示），留空不改" : "填写 API Key";
    $("cfgTemp").value = c.temperature;
    $("cfgTimeout").value = c.readTimeoutSeconds;
    $("cfgNoThink").checked = !!c.noThink;
    $("cfgThinking").checked = !!c.thinkingDisabled;
    $("cfgState").textContent = "当前 " + c.mode + " · " + (c.chatModel || "—")
      + " · " + (c.modelId || "—") + (c.source === "file" ? " · 已落盘" : " · 启动参数");
    syncMode();
  }

  function syncMode() {
    var stub = mode() === "stub";
    ["cfgBase", "cfgModel", "cfgKey", "cfgTemp", "cfgTimeout", "cfgNoThink", "cfgThinking"].forEach(function (id) {
      $(id).disabled = stub;
    });
    if (mode() === "local") {
      if (!$("cfgBase").value) $("cfgBase").value = LOCAL_BASE;
      if (!$("cfgModel").value) $("cfgModel").value = "qwen3:8b";
      if (!$("cfgKey").value && $("cfgKey").placeholder.indexOf("已保存") < 0) $("cfgKey").value = "ollama";
    }
  }

  function collect() {
    return {
      mode: mode(),
      baseUrl: $("cfgBase").value.trim(),
      chatModel: $("cfgModel").value.trim(),
      apiKey: $("cfgKey").value.trim(),
      temperature: Number($("cfgTemp").value || 0.2),
      noThink: $("cfgNoThink").checked,
      thinkingDisabled: $("cfgThinking").checked,
      readTimeoutSeconds: Number($("cfgTimeout").value || 120)
    };
  }

  function parse(res) {
    return res.json().catch(function () { return {}; }).then(function (data) {
      if (!res.ok) throw new Error(data.message || data.error || ("请求失败 " + res.status));
      return data;
    });
  }

  function loadConfig() {
    fetch(CFG, { headers: { Accept: "application/json" } })
      .then(parse)
      .then(fill)
      .catch(function (err) {
        $("cfgState").textContent = "读取失败";
        say("读不到模型配置：" + err.message, "is-bad");
      });
  }

  document.querySelectorAll('input[name="aiMode"]').forEach(function (el) {
    el.addEventListener("change", syncMode);
  });

  $("cfgSave").onclick = function () {
    var btn = $("cfgSave");
    btn.disabled = true;
    say("正在保存并切换…", "is-info");
    fetch(CFG, {
      method: "PUT",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ config: collect() })
    }).then(parse).then(function (view) {
      fill(view);
      say("已生效：" + view.mode + " · " + (view.chatModel || "stub") + "（" + view.modelId + "）", "is-ok");
      load();
    }).catch(function (err) {
      say("没有保存：" + err.message, "is-bad");
    }).then(function () { btn.disabled = false; });
  };

  $("cfgTest").onclick = function () {
    var btn = $("cfgTest");
    btn.disabled = true;
    say("正在发一条最小请求…", "is-info");
    fetch(CFG + "/test", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ config: collect() })
    }).then(parse).then(function (r) {
      if (r.ok) say("连通成功 · " + r.ms + " ms · 回复：" + (r.reply || "（空）"), "is-ok");
      else say("连不通 · " + r.ms + " ms\n" + (r.error || "未知错误"), "is-bad");
    }).catch(function (err) {
      say("测试失败：" + err.message, "is-bad");
    }).then(function () { btn.disabled = false; });
  };

  $("cfgLocalPreset").onclick = function () {
    var radio = document.querySelector('input[name="aiMode"][value="local"]');
    if (radio) radio.checked = true;
    $("cfgBase").value = LOCAL_BASE;
    $("cfgModel").value = "qwen3:8b";
    $("cfgKey").value = "ollama";
    $("cfgTimeout").value = 120;
    $("cfgNoThink").checked = true;
    $("cfgThinking").checked = false;
    syncMode();
    say("已填入本地默认值：Ollama + qwen3:8b。点「检测本地模型」确认它在跑。", "is-info");
  };

  function localCall(path, method) {
    var base = $("cfgBase").value.trim() || LOCAL_BASE;
    return fetch(LOCAL_API + path + "?baseUrl=" + encodeURIComponent(base), {
      method: method || "GET",
      headers: { Accept: "application/json" }
    }).then(parse);
  }

  function reportLocal(status, prefix) {
    if (status.running) {
      var models = (status.models || []).join("、");
      say(prefix + "：本地模型在线（" + status.baseUrl + "）"
        + (models ? "\n可用模型：" + models : "\n本机还没有拉取模型，可先运行 ollama pull qwen3:8b"), "is-ok");
    } else {
      say(prefix + "：没有连上 " + status.baseUrl + (status.error ? "\n" + status.error : ""), "is-bad");
    }
  }

  $("cfgLocalCheck").onclick = function () {
    say("正在检测本地 Ollama…", "is-info");
    localCall("/status").then(function (s) { reportLocal(s, "检测完成"); })
      .catch(function (err) { say("检测失败：" + err.message, "is-bad"); });
  };

  $("cfgLocalStart").onclick = function () {
    var btn = $("cfgLocalStart");
    btn.disabled = true;
    say("正在尝试启动本机 Ollama（最多等 8 秒）…", "is-info");
    localCall("/start", "POST").then(function (s) { reportLocal(s, "启动结果"); })
      .catch(function (err) { say("启动失败：" + err.message, "is-bad"); })
      .then(function () { btn.disabled = false; });
  };

  loadConfig();
})();
