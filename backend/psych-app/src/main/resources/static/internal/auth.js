/**
 * 内部控制台的钥匙：所有 /api/internal 与知识库写请求都带上 X-Internal-Token。
 *
 * 开发档（h2 / pg / http）会把默认令牌通过 /api/internal-hint 告诉页面，
 * 这里自动填入并在右下角提示一句——验收时不该先让人去翻配置文件。
 * 生产档不开 dev-hint，这个接口不注册，于是退回"弹窗输入"的老路。
 *
 * 之所以放在最前面加载，是为了包住后面页面自己的 fetch —— 它们不需要知道鉴权这回事。
 */
(function () {
  var KEY = "sm-internal-token";
  var raws = window.fetch;
  var hint = null;
  var hintPromise = null;
  var prompting = false;

  function guarded(url) {
    return url.indexOf("/api/internal/") === 0 ||
      url.indexOf("/api/knowledge/documents") === 0 ||
      url.indexOf("/api/knowledge/stats") === 0;
  }

  function banner(text) {
    try {
      var el = document.createElement("div");
      el.textContent = text;
      el.title = "点击关闭";
      el.style.cssText = [
        "position:fixed", "right:16px", "bottom:16px", "z-index:9999",
        "max-width:380px", "padding:9px 12px", "border-radius:8px",
        "background:#0d7a63", "color:#fff", "cursor:pointer",
        "font:12px/1.6 -apple-system,'PingFang SC','Microsoft YaHei',sans-serif",
        "box-shadow:0 6px 20px rgba(0,0,0,.16)"
      ].join(";");
      el.onclick = function () { el.remove(); };
      document.body.appendChild(el);
    } catch (e) { /* 页面还没 body 时忽略 */ }
    console.info("[internal] " + text);
  }

  function ensureToken() {
    var saved = localStorage.getItem(KEY);
    if (saved) return Promise.resolve(saved);
    if (hintPromise) return hintPromise;

    hintPromise = raws.call(window, "/api/internal-hint", { headers: { Accept: "application/json" } })
      .then(function (res) { return res.ok ? res.json() : null; })
      .then(function (data) {
        if (data && data.token) {
          hint = data;
          localStorage.setItem(KEY, data.token);
          banner("本机开发档：已自动填入内部令牌 " + data.token + "（正式环境请关闭 psych.internal.dev-hint）");
          return data.token;
        }
        return null;
      })
      .catch(function () { return null; });

    return hintPromise;
  }

  function askOnce() {
    if (prompting) return;
    prompting = true;
    var dflt = (hint && hint.token) || localStorage.getItem(KEY) || "dev-internal-token";
    var entered = window.prompt(
      "需要内部令牌（X-Internal-Token）。\n本机开发档默认是 dev-internal-token，直接回车即可：", dflt);
    if (entered) {
      localStorage.setItem(KEY, entered.trim());
      location.reload();
    } else {
      prompting = false;
    }
  }

  window.fetch = function (input, init) {
    var url = typeof input === "string" ? input : (input && input.url) || "";
    if (!guarded(url)) return raws.call(window, input, init);

    return ensureToken().then(function (token) {
      init = init || {};
      init.headers = Object.assign({}, init.headers || {});
      if (token) init.headers["X-Internal-Token"] = token;
      return raws.call(window, input, init);
    }).then(function (res) {
      if (res.status === 401) askOnce();
      return res;
    });
  };

  // 页面一打开就先问提示，避免第一屏全是 401
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", function () { ensureToken(); });
  } else {
    ensureToken();
  }
})();
