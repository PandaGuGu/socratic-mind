/**
 * 账号页。
 *
 * 四件容易被写错的事，都在这里显式处理：
 * 1. 登录后要换令牌，但**不能把设备令牌弄丢**——否则退出登录时本机的匿名记录就找不回来了。
 *    设备令牌先存进 sm-device-token，退出时再换回来。
 * 2. 换身份必须清掉 sm-session：旧会话 id 属于旧身份，留着只会让房间读到一个 404。
 * 3. 登录/注册不再自动认领。服务端只回 pendingClaims，这里必须等用户点"归到这个账号"。
 * 4. 注册（或重新生成）的恢复码只出现一次；用户没点"我已抄下"之前，不跳走。
 */
(function () {
  var API = "/api/auth";
  var TOKEN_KEY = "sm-token";
  var DEVICE_KEY = "sm-device-token";
  var SESSION_KEY = "sm-session";

  var $ = function (id) { return document.getElementById(id); };
  var errorBox = $("error");
  var actionError = $("actionError");

  /** 当前流程的临时状态：恢复码与待认领数。 */
  var state = { pending: 0, recoveryCode: null, rotating: false };

  function token() { return localStorage.getItem(TOKEN_KEY); }

  function isDeviceToken(value) {
    return !!value && value.indexOf("v1.") === 0;
  }

  function showError(message) {
    errorBox.textContent = message || "";
    errorBox.hidden = !message;
  }

  function showActionError(message) {
    actionError.textContent = message || "";
    actionError.hidden = !message;
  }

  function api(path, body, method) {
    var headers = { "Content-Type": "application/json", "Accept": "application/json" };
    var current = token();
    if (current) headers["X-User-Token"] = current;
    return fetch(API + path, {
      method: method || "POST",
      headers: headers,
      body: method === "GET" ? undefined : JSON.stringify(body || {})
    }).then(function (res) {
      var fresh = res.headers.get("X-User-Token");
      if (fresh) localStorage.setItem(TOKEN_KEY, fresh);
      return res.json().catch(function () { return {}; }).then(function (data) {
        if (!res.ok) {
          throw new Error(data.message || "请求失败（" + res.status + "）");
        }
        return data;
      });
    });
  }

  function setBusy(busy) {
    Array.prototype.forEach.call(document.querySelectorAll(".submit"), function (btn) {
      btn.disabled = busy;
    });
  }

  function short(value) {
    if (!value) return "—";
    var d = new Date(value);
    if (isNaN(d.getTime())) return String(value);
    var p = function (n) { return (n < 10 ? "0" : "") + n; };
    return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate())
      + " " + p(d.getHours()) + ":" + p(d.getMinutes());
  }

  function renderFacts(account) {
    if (!account) return;
    $("factEmail").textContent = account.email || "—";
    $("factName").textContent = account.displayName || "—";
    $("factCreated").textContent = short(account.createdAt);
    $("factLogin").textContent = short(account.lastLoginAt);
  }

  /* ---------- 进入账号后的三条分支 ---------- */

  function adopt(data) {
    var current = token();
    if (isDeviceToken(current) && !localStorage.getItem(DEVICE_KEY)) {
      localStorage.setItem(DEVICE_KEY, current);
    }
    localStorage.setItem(TOKEN_KEY, data.token);
    localStorage.removeItem(SESSION_KEY);

    state.pending = data.pendingClaims || 0;
    state.recoveryCode = data.recoveryCode || null;

    $("signedOut").hidden = true;
    $("signedIn").hidden = false;
    $("title").textContent = "已经登录。";
    $("lede").textContent = "这台设备与账号都能看到这些记录；退出登录只会退回到这台设备的匿名身份。";
    renderFacts(data.account);
    $("claimedNote").hidden = true;
    showError("");

    if (state.recoveryCode) {
      showRecovery("只显示这一次 · 抄下来");
      return;
    }
    decideNext();
  }

  /** 有恢复码先停；没有就进入"认领 / 直接回房间"。 */
  function decideNext() {
    if (state.recoveryCode) return;
    if (state.pending > 0) {
      $("claimCount").textContent = String(state.pending);
      $("claimBox").hidden = false;
      $("recoveryBox").hidden = true;
      $("title").textContent = "还有一件事要你决定。";
      return;
    }
    $("claimBox").hidden = true;
    $("lede").textContent = "正在回到房间…";
    setTimeout(function () { location.href = "/room/"; }, 700);
  }

  function showRecovery(kicker) {
    $("recoveryBox").hidden = false;
    $("claimBox").hidden = true;
    $("recoveryCode").textContent = state.recoveryCode;
    $("recoveryBox").querySelector(".recovery-kicker").textContent = kicker;
    $("title").textContent = "先抄下这串恢复码。";
    $("lede").textContent = "它只在现在出现一次；没有它，忘记口令就等于丢了账号。";
  }

  $("recoveryDone").onclick = function () {
    state.recoveryCode = null;
    $("recoveryBox").hidden = true;
    $("title").textContent = "已经登录。";
    if (state.rotating) {
      state.rotating = false;
      $("lede").textContent = "新的恢复码已生效，旧码作废。";
      return;
    }
    $("lede").textContent = state.pending > 0
      ? "这台设备上还有匿名记录，等你决定要不要归到账号下。"
      : "正在回到房间…";
    decideNext();
  };

  $("copyRecovery").onclick = function () {
    copyText(state.recoveryCode || "").then(function (ok) {
      $("copyRecovery").textContent = ok ? "已复制" : "复制失败，请手动选中";
    });
  };

  function copyText(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      return navigator.clipboard.writeText(text).then(function () { return true; })
        .catch(function () { return false; });
    }
    var area = document.createElement("textarea");
    area.value = text;
    document.body.appendChild(area);
    area.select();
    var ok = false;
    try { ok = document.execCommand("copy"); } catch (e) { ok = false; }
    area.remove();
    return Promise.resolve(ok);
  }

  $("claimYes").onclick = function () {
    $("claimYes").disabled = true;
    api("/claim", {}).then(function (data) {
      var claimed = data.claimed || 0;
      $("claimBox").hidden = true;
      var note = $("claimedNote");
      note.hidden = false;
      note.textContent = "已把 " + claimed + " 轮记录归到这个账号。换设备登录也能看到。";
      $("lede").textContent = "正在回到房间…";
      setTimeout(function () { location.href = "/room/"; }, 900);
    }).catch(function (err) {
      $("claimYes").disabled = false;
      $("claimBox").querySelector(".claim-body").textContent = err.message;
    });
  };

  $("claimNo").onclick = function () {
    state.pending = 0;
    $("claimBox").hidden = true;
    $("lede").textContent = "这些记录只留在这台设备上。正在回到房间…";
    setTimeout(function () { location.href = "/room/"; }, 700);
  };

  /* ---------- 未登录 ---------- */

  function renderSignedOut() {
    $("signedIn").hidden = true;
    $("signedOut").hidden = false;
    // 有设备令牌 = 这台设备可能已经有匿名记录，值得提示"登录后要你确认"
    $("claimNote").hidden = !isDeviceToken(token());
  }

  function tab(which) {
    ["login", "register", "recover"].forEach(function (key) {
      var on = key === which;
      $("tab" + key.charAt(0).toUpperCase() + key.slice(1)).classList.toggle("on", on);
      $("tab" + key.charAt(0).toUpperCase() + key.slice(1)).setAttribute("aria-selected", String(on));
      $(key + "Form").hidden = !on;
    });
    showError("");
  }

  $("tabLogin").onclick = function () { tab("login"); };
  $("tabRegister").onclick = function () { tab("register"); };
  $("tabRecover").onclick = function () { tab("recover"); };

  $("loginForm").onsubmit = function (e) {
    e.preventDefault();
    showError("");
    setBusy(true);
    api("/login", { email: $("loginEmail").value, password: $("loginPassword").value })
      .then(adopt)
      .catch(function (err) { showError(err.message); })
      .then(function () { setBusy(false); });
  };

  $("registerForm").onsubmit = function (e) {
    e.preventDefault();
    showError("");
    setBusy(true);
    api("/register", {
      email: $("regEmail").value,
      password: $("regPassword").value,
      displayName: $("regName").value
    })
      .then(adopt)
      .catch(function (err) { showError(err.message); })
      .then(function () { setBusy(false); });
  };

  $("recoverForm").onsubmit = function (e) {
    e.preventDefault();
    showError("");
    setBusy(true);
    api("/recover", {
      email: $("recEmail").value,
      recoveryCode: $("recCode").value,
      newPassword: $("recPassword").value
    })
      .then(function (data) {
        adopt(data);
        // 恢复码已用掉；提醒用户登录后重新生成一枚
        $("claimedNote").hidden = false;
        $("claimedNote").textContent = "口令已重置，旧恢复码已作废。到“安全”里生成一枚新的并抄下来。";
      })
      .catch(function (err) { showError(err.message); })
      .then(function () { setBusy(false); });
  };

  /* ---------- 已登录 ---------- */

  $("logout").onclick = function () {
    var device = localStorage.getItem(DEVICE_KEY);
    if (device) {
      localStorage.setItem(TOKEN_KEY, device);
      localStorage.removeItem(DEVICE_KEY);
    } else {
      localStorage.removeItem(TOKEN_KEY);
    }
    localStorage.removeItem(SESSION_KEY);
    location.reload();
  };

  $("passwordForm").onsubmit = function (e) {
    e.preventDefault();
    showActionError("");
    setBusy(true);
    api("/password", {
      currentPassword: $("curPassword").value,
      newPassword: $("newPassword").value
    }).then(function (data) {
      renderFacts(data.account);
      $("passwordForm").reset();
      showActionError("");
      $("claimedNote").hidden = false;
      $("claimedNote").textContent = "口令已改。其他设备上的登录已失效，这台设备继续可用。";
    }).catch(function (err) {
      showActionError(err.message);
    }).then(function () { setBusy(false); });
  };

  $("logoutAll").onclick = function () {
    if (!window.confirm("退出其他设备？当前这台不受影响。")) return;
    $("logoutAll").disabled = true;
    api("/logout-all", {}).then(function (data) {
      renderFacts(data.account);
      $("claimedNote").hidden = false;
      $("claimedNote").textContent = "已退出其他设备。这台设备的登录继续有效。";
    }).catch(function (err) {
      showActionError(err.message);
    }).then(function () { $("logoutAll").disabled = false; });
  };

  $("rotateRecovery").onclick = function () {
    if (!window.confirm("生成新的恢复码？旧码会立即作废。")) return;
    $("rotateRecovery").disabled = true;
    api("/recovery-code", {}).then(function (data) {
      state.recoveryCode = data.recoveryCode;
      state.rotating = true;
      showRecovery("新恢复码 · 只显示这一次");
    }).catch(function (err) {
      showActionError(err.message);
    }).then(function () { $("rotateRecovery").disabled = false; });
  };

  $("deleteForm").onsubmit = function (e) {
    e.preventDefault();
    var box = $("dangerError");
    box.hidden = true;
    if (!$("deleteAck").checked) {
      box.textContent = "先勾选“我知道注销以后找不回来”。";
      box.hidden = false;
      return;
    }
    if (!window.confirm("注销账号？这一步不能撤销。")) return;

    var btn = $("deleteForm").querySelector(".danger-btn");
    btn.disabled = true;
    api("/delete", { password: $("deletePassword").value })
      .then(function (data) {
        var device = localStorage.getItem(DEVICE_KEY);
        if (device) {
          localStorage.setItem(TOKEN_KEY, device);
          localStorage.removeItem(DEVICE_KEY);
        } else {
          localStorage.removeItem(TOKEN_KEY);
        }
        localStorage.removeItem(SESSION_KEY);
        try {
          sessionStorage.setItem("sm-deleted", String(data.releasedSessions || 0));
        } catch (err) { /* 隐私模式下存不了，不影响注销本身 */ }
        location.href = "/account/?deleted=1";
      })
      .catch(function (err) {
        box.textContent = err.message;
        box.hidden = false;
        btn.disabled = false;
      });
  };

  /** 注销回跳：说清楚"账号没了，但这台设备上的记录还在"。 */
  function showDeletedNote() {
    var params = new URLSearchParams(location.search);
    if (params.get("deleted") !== "1") return;
    var released = 0;
    try {
      released = Number(sessionStorage.getItem("sm-deleted") || 0);
      sessionStorage.removeItem("sm-deleted");
    } catch (err) { /* 隐私模式 */ }
    var note = $("deletedNote");
    note.hidden = false;
    note.textContent = "账号已注销，登录方式已失效。"
      + (released > 0
        ? "这 " + released + " 轮记录回到了这台设备名下，仍能在房间里看到。"
        : "这台设备上的记录仍能在房间里看到。");
  }

  // 初次渲染：没有令牌就直接是未登录，不必打一次必然 401 的请求
  showDeletedNote();
  var current = token();
  if (!current) {
    renderSignedOut();
    return;
  }
  fetch(API + "/me", { headers: { "X-User-Token": current, "Accept": "application/json" } })
    .then(function (res) { return res.json(); })
    .then(function (data) {
      if (data && data.signedIn) {
        renderFacts(data.account);
        $("signedOut").hidden = true;
        $("signedIn").hidden = false;
      } else {
        renderSignedOut();
      }
    })
    .catch(renderSignedOut);
})();
