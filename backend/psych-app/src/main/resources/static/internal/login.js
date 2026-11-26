/**
 * 登录页。
 *
 * 刻意不加载 auth.js：那个脚本会在 401 时把页面推回登录页，
 * 而登录页本身就是这条重定向链的终点。
 *
 * 开发档账号块默认隐藏，只有 /api/internal-hint 确认"这是本机开发档"时才显示。
 * 静态页面里写死一组默认口令，看起来方便，实则是把默认凭据印在了生产页面上——
 * 关掉 dev-hint 之后那一块必须消失。
 */
(function () {
  var $ = function (id) { return document.getElementById(id); };
  var DEV_USER = "admin";
  var DEV_PASS = "dev-admin-pass";

  function next() {
    var q = new URLSearchParams(location.search).get("next");
    // 只接受站内相对路径：?next=https://evil 会把登录后的用户送去钓鱼页。
    if (!q || q.indexOf("//") >= 0 || q.indexOf(":") >= 0) return "./index.html";
    return q.charAt(0) === "/" || q.charAt(0) === "." ? q : "./" + q;
  }

  function submit() {
    var btn = $("submitBtn");
    var msg = $("msg");
    btn.disabled = true;
    msg.textContent = "";

    return fetch("/api/operator/login", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ username: $("username").value, password: $("password").value })
    }).then(function (res) {
      return res.json().catch(function () { return {}; }).then(function (data) {
        if (!res.ok) throw new Error(data.error || ("登录失败（" + res.status + "）"));
        location.href = next();
      });
    }).catch(function (err) {
      msg.textContent = err.message;
      btn.disabled = false;
    });
  }

  $("loginForm").addEventListener("submit", function (e) {
    e.preventDefault();
    submit();
  });

  // 开发档：显示显式账号，并提供一键填入
  fetch("/api/internal-hint", { headers: { Accept: "application/json" } })
    .then(function (res) { return res.ok ? res.json() : null; })
    .then(function (data) {
      if (!data || data.token !== "dev-internal-token") return;
      $("devUser").textContent = DEV_USER;
      $("devPass").textContent = DEV_PASS;
      $("devBox").hidden = false;
      if (!$("username").value) $("username").value = DEV_USER;
      $("fillBtn").addEventListener("click", function () {
        $("username").value = DEV_USER;
        $("password").value = DEV_PASS;
        submit();
      });
    })
    .catch(function () { /* 拿不到提示就当生产环境：不显示账号块 */ });
})();
