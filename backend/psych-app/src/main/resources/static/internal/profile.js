/**
 * 个人设置：读取当前操作者、改口令、退出登录。
 *
 * 组织模式关闭时 /api/operator/me 会 401，这不是错误——页面要如实说明
 * "现在用的是内部令牌，没有账号可改"，而不是弹一个登录框。
 */
(function () {
  var $ = function (id) { return document.getElementById(id); };

  function say(id, text, ok) {
    var el = $(id);
    el.textContent = text;
    el.className = "msg " + (ok ? "ok" : "bad");
  }

  function parse(res) {
    return res.json().catch(function () { return {}; }).then(function (data) {
      if (!res.ok) throw new Error(data.error || data.message || ("请求失败 " + res.status));
      return data;
    });
  }

  function showTokenMode() {
    $("whoName").textContent = "未登录操作者";
    $("whoMeta").textContent = "当前请求只带内部令牌";
    $("tokenNote").hidden = false;
  }

  function boot() {
    fetch("/api/operator/me", { headers: { Accept: "application/json" } })
      .then(function (res) {
        if (res.status === 401) return null;
        return parse(res);
      })
      .then(function (data) {
        if (!data || !data.user) {
          showTokenMode();
          return;
        }
        var user = data.user;
        var name = String(user.name || user.id || "操作者");
        $("whoName").textContent = name;
        $("whoMeta").textContent = "已登录" + (user.role ? " · " + user.role : "");
        $("whoAvatar").innerHTML = '<span>' + escapeHtml(name.charAt(0)) + '</span>';
        $("kvId").textContent = user.id || "—";
        $("kvRole").textContent = user.role || "—";
        var scope = [];
        if (user.tenantId) scope.push("租户 " + user.tenantId);
        if (user.teamId) scope.push("团队 " + user.teamId);
        $("kvScope").textContent = scope.length ? scope.join(" / ") : "—";
        $("whoDetail").hidden = false;
        $("logoutRow").hidden = false;
        $("passwordCard").hidden = false;
      })
      .catch(function (err) {
        showTokenMode();
        $("whoMeta").textContent = "读取身份失败：" + err.message;
      });
  }

  function escapeHtml(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  $("passwordForm").addEventListener("submit", function (e) {
    e.preventDefault();
    var oldPass = $("oldPassword").value;
    var next = $("newPassword").value;
    var confirm = $("confirmPassword").value;
    if (next !== confirm) {
      say("passwordMsg", "两次输入的新口令不一致", false);
      return;
    }
    var btn = $("savePassword");
    btn.disabled = true;
    say("passwordMsg", "正在保存…", true);
    fetch("/api/operator/password", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ oldPassword: oldPass, newPassword: next })
    }).then(parse).then(function (data) {
      say("passwordMsg", data.note || "口令已修改，请重新登录", true);
      setTimeout(function () { location.href = "./login.html"; }, 1600);
    }).catch(function (err) {
      say("passwordMsg", err.message, false);
      btn.disabled = false;
    });
  });

  $("logoutBtn").addEventListener("click", function () {
    fetch("/api/operator/logout", { method: "POST", headers: { Accept: "application/json" } })
      .then(function () { location.href = "./login.html"; })
      .catch(function (err) { say("logoutMsg", "退出失败：" + err.message, false); });
  });

  boot();
})();
