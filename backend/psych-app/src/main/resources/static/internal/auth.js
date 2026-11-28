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
  var KEY_OP = "sm-operator-token";
  var raws = window.fetch;
  var hint = null;
  var hintPromise = null;
  var prompting = false;

  function guarded(url) {
    return url.indexOf("/api/internal/") === 0 ||
      url.indexOf("/api/knowledge/documents") === 0 ||
      url.indexOf("/api/knowledge/stats") === 0;
  }

  function csrfToken() {
    var m = document.cookie.match(/(?:^|;\s*)sm_csrf=([^;]+)/);
    return m ? decodeURIComponent(m[1]) : null;
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
          if (data.operatorToken) localStorage.setItem(KEY_OP, data.operatorToken);
          banner("本机开发档：已自动填入内部令牌 " + data.token + "（正式环境请关闭 psych.internal.dev-hint）");
          return data.token;
        }
        return null;
      })
      .catch(function () { return null; });

    return hintPromise;
  }

  function requireLogin() {
    // 组织模式关闭时没有登录页，仍走"问一次内部令牌"的老路。
    if (document.body && document.body.dataset.login === "off") { askOnce(); return; }
    if (location.pathname.indexOf("login.html") >= 0) return;
    var next = location.pathname + location.search;
    location.href = "./login.html?next=" + encodeURIComponent(next);
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
    // /api/operator/me 也要带上操作者令牌：开发档的“登录身份”就存在 localStorage 里，
    // 不带这个头，个人页与审核页会误判成“未登录”。
    var isMe = url.indexOf("/api/operator/me") === 0;
    if (!guarded(url) && !isMe) return raws.call(window, input, init);

    return ensureToken().then(function (token) {
      init = init || {};
      init.headers = Object.assign({}, init.headers || {});
      if (token) init.headers["X-Internal-Token"] = token;
      var op = localStorage.getItem(KEY_OP);
      if (op) init.headers["X-Operator-Token"] = op;
      var method = (init.method || "GET").toUpperCase();
      if (method !== "GET" && method !== "HEAD") {
        var csrf = csrfToken();
        if (csrf) init.headers["X-CSRF-Token"] = csrf;
      }
      var pending = raws.call(window, input, init);
      // 任何配置写操作后刷新“待审核”徽标：保存完立刻就能看到提醒。
      if (method !== "GET" && method !== "HEAD") {
        pending.then(function () { refreshReviewBadge(); }).catch(function () {});
      }
      return pending;
    }).then(function (res) {
      // 身份探测失败只是“没登录”，不该把用户推去登录页
      if (res.status === 401 && !isMe) requireLogin();
      return res;
    });
  };

  // 页面一打开就先问提示，避免第一屏全是 401
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", function () {
      ensureToken();
      mountAccountChip();
      mountReviewBadge();
    });
  } else {
    ensureToken();
    mountAccountChip();
    mountReviewBadge();
  }

  var reviewTimer = null;
  var reviewDismissed = false;

  function refreshReviewBadge() {
    clearTimeout(reviewTimer);
    reviewTimer = setTimeout(loadReviewState, 500);
  }

  function mountReviewBadge() {
    loadReviewState();
  }

  function loadReviewState() {
    try {
      window.fetch("/api/internal/reviews", { headers: { Accept: "application/json" } })
        .then(function (res) { return res.ok ? res.json() : null; })
        .then(function (data) {
          if (!data) return;
          renderReviewState((data.pending || 0) + (data.pendingSessions || 0),
            data.pending || 0, data.pendingSessions || 0);
        })
        .catch(function () { /* 没有内部权限的页面（如登录页）忽略 */ });
    } catch (e) { /* 忽略 */ }
  }

  function renderReviewState(total, pendingConfig, pendingSessions) {
    try {
      var link = document.querySelector('.sb-item[href="./review.html"]');
      if (link) {
        var badge = link.querySelector(".sb-badge");
        if (total <= 0) {
          if (badge) badge.remove();
        } else {
          if (!badge) {
            badge = document.createElement("span");
            badge.className = "sb-badge";
            link.appendChild(badge);
          }
          badge.textContent = String(total);
          badge.title = pendingConfig + " 项配置待审 / " + pendingSessions + " 条会话待复核";
        }
      }
      var box = document.getElementById("reviewReminder");
      var foot = document.querySelector(".sb-foot");
      if (total <= 0 || reviewDismissed) {
        if (box) box.remove();
        if (foot) {
          var old = foot.querySelector(".sb-review-note");
          if (old) old.remove();
        }
        return;
      }
      if (!box && foot) {
        box = document.createElement("a");
        box.id = "reviewReminder";
        box.className = "sb-review-note";
        box.href = "./review.html";
        foot.appendChild(box);
      }
      if (!box) return;
      box.textContent = total + " 项待审核 / 待复核，去处理 →";
      box.title = pendingConfig + " 项配置待审 / " + pendingSessions + " 条会话待复核";
    } catch (e) { /* 忽略 */ }
  }

  /**
   * 顶栏个人入口。默认就是一个圆形头像；登录了操作者账号时用首字母，
   * 没登录（内部令牌模式）也保留入口，点进去会说明当前模式。
   */
  /**
   * 按职责收窄导航与操作入口。
   *
   * 只是"少一次误点"：真正的边界在 InternalAuthInterceptor，前端藏不藏都不影响它。
   * 未登录（内部令牌模式）时不限制——那把钥匙本来就不代表某个人。
   */
  function applyPerm(role) {
    if (!role) return;
    document.documentElement.dataset.role = role;
    var can = {
      manage: role === "admin",
      review: role === "admin" || role === "reviewer",
      ops: role === "admin" || role === "operator"
    };
    var nodes = document.querySelectorAll("[data-perm]");
    for (var i = 0; i < nodes.length; i++) {
      var need = nodes[i].getAttribute("data-perm");
      if (need && can[need] === false) nodes[i].hidden = true;
    }

    // 分组标题要跟着走：底下的项全被藏了，"配置"两个字还杵在那里就是一句废话。
    // 按 DOM 顺序往后数，直到下一个 .sb-label 为止；有一个还露着，这个标题就留着。
    var labels = document.querySelectorAll(".sb-nav .sb-label");
    for (var j = 0; j < labels.length; j++) {
      var node = labels[j].nextElementSibling;
      var anyVisible = false;
      while (node && !node.classList.contains("sb-label")) {
        if (!node.hidden) anyVisible = true;
        node = node.nextElementSibling;
      }
      labels[j].hidden = !anyVisible;
    }
    // 身份是异步拿到的，页面自己的按钮要等这一下才知道该不该显示
    try {
      document.dispatchEvent(new CustomEvent("sm:perm", { detail: { role: role, can: can } }));
    } catch (e) { /* 老浏览器忽略 */ }
  }

  function mountAccountChip() {
    try {
      var actions = document.querySelector(".topbar .top-actions");
      if (!actions || actions.querySelector(".acct-chip")) return;
      var link = document.createElement("a");
      link.className = "acct-chip";
      link.href = "./profile.html";
      link.title = "个人设置";
      link.setAttribute("aria-label", "个人设置");
      link.innerHTML = '<span class="acct-chip-avatar" aria-hidden="true">'
        + '<svg viewBox="0 0 16 16"><circle cx="8" cy="5.6" r="2.5"></circle>'
        + '<path d="M3.2 13.4c.7-2.7 2.6-3.9 4.8-3.9s4.1 1.2 4.8 3.9"></path></svg>'
        + '</span>';
      actions.appendChild(link);

      raws.call(window, "/api/operator/me", { headers: { Accept: "application/json" } })
        .then(function (res) { return res.ok ? res.json() : null; })
        .then(function (data) {
          var user = data && data.user;
          if (!user) return;
          applyPerm(user.role);
          var name = String(user.name || "").trim();
          if (!name) return;
          link.title = name + " · 个人设置";
          link.querySelector(".acct-chip-avatar").textContent = name.charAt(0);
        })
        .catch(function () { /* 令牌模式：保留默认头像 */ });
    } catch (e) { /* 没有顶栏的页面忽略 */ }
  }
})();
