/**
 * 成员与职责：管理员在这里建运营与审核，并分配"能改什么"。
 *
 * 这里不做任何权限判断——凡事先请求后台，403 就把服务端的话原样显示出来。
 * 前端藏按钮只是少一次误点，真正的边界在 InternalAuthInterceptor。
 */
(function () {
  var $ = function (id) { return document.getElementById(id); };
  var API = "/api/internal/members";
  var ROLES = {
    admin: "管理员",
    operator: "运营",
    reviewer: "审核",
    viewer: "只读"
  };

  function esc(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function parse(res) {
    return res.json().catch(function () { return {}; }).then(function (data) {
      if (!res.ok) throw new Error(data.message || data.error || ("请求失败 " + res.status));
      return data;
    });
  }

  function say(id, text, ok) {
    var el = $(id);
    el.textContent = text || "";
    el.className = "msg " + (ok ? "ok" : "bad");
  }

  function row(m) {
    var role = ROLES[m.role] || m.role || "未分配";
    var live = !m.revokedAt;
    return '<tr data-id="' + esc(m.id) + '">'
      + '<td><b>' + esc(m.name) + '</b><div class="hint" style="margin:0">' + esc(m.id) + '</div></td>'
      + '<td><span class="role-tag ' + esc(m.role) + '">' + esc(role) + '</span></td>'
      + '<td' + (m.username ? "" : ' class="is-off"') + '>' + (m.username ? esc(m.username) : "只能用令牌") + '</td>'
      + '<td' + (live ? "" : ' class="is-off"') + '>' + (live ? "在用" : "已撤销") + '</td>'
      + '<td><div class="row-act">'
      + (live
        ? '<button class="link-act" data-reset="' + esc(m.id) + '" type="button">重设口令</button>'
          + '<button class="link-act danger" data-revoke="' + esc(m.id) + '" type="button">撤销</button>'
        : "")
      + '</div></td>'
      + '</tr>';
  }

  function render(list) {
    var body = $("memberRows");
    if (!list.length) {
      body.innerHTML = '<tr><td colspan="5" class="empty">还没有成员</td></tr>';
      return;
    }
    body.innerHTML = list.map(row).join("");
    $("memberNote").textContent = "共 " + list.length + " 位成员。撤销会立刻让他的所有设备下线。";
  }

  function load() {
    return fetch(API, { headers: { Accept: "application/json" } })
      .then(parse)
      .then(render)
      .catch(function (err) {
        $("memberRows").innerHTML =
          '<tr><td colspan="5" class="empty">' + esc(err.message) + "</td></tr>";
        if (/职责|权限|403/.test(err.message)) {
          $("createCard").hidden = true;
        }
      });
  }

  $("createForm").addEventListener("submit", function (e) {
    e.preventDefault();
    var btn = $("createBtn");
    btn.disabled = true;
    say("createMsg", "正在创建…", true);
    fetch(API, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        name: $("newName").value.trim(),
        role: $("newRole").value,
        username: $("newUsername").value.trim(),
        password: $("newPassword").value
      })
    })
      .then(parse)
      .then(function (data) {
        say("createMsg", "已创建 " + data.member.name + "（" + (ROLES[data.member.role] || data.member.role)
          + "）。口令请当面转交。", true);
        $("createForm").reset();
        return load();
      })
      .catch(function (err) { say("createMsg", err.message, false); })
      .finally(function () { btn.disabled = false; });
  });

  document.addEventListener("click", function (e) {
    var reset = e.target.closest("[data-reset]");
    if (reset) {
      var pwd = window.prompt("给这位成员设一个新口令（至少 6 位，会立刻让他的旧会话下线）");
      if (!pwd) return;
      fetch(API + "/" + encodeURIComponent(reset.dataset.reset) + "/password", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ password: pwd })
      }).then(parse)
        .then(function () { say("memberNote", "口令已重设，旧会话已下线", true); })
        .catch(function (err) { say("memberNote", err.message, false); });
      return;
    }
    var revoke = e.target.closest("[data-revoke]");
    if (revoke) {
      if (!window.confirm("撤销后这个人立刻不能登录、所有设备下线。确定？")) return;
      fetch(API + "/" + encodeURIComponent(revoke.dataset.revoke) + "/revoke", { method: "POST" })
        .then(parse)
        .then(load)
        .catch(function (err) { say("memberNote", err.message, false); });
    }
  });

  load();
})();
