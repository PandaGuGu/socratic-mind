/**
 * 全局外壳脚本：侧栏折叠、小屏抽屉、页内锚点、卡片折叠。
 *
 * 两个约定：
 * 1. 折叠状态存 localStorage 的 sm-nav-collapsed，三个页面共用，翻页不会跳回展开。
 * 2. 侧栏里的「本页」锚点靠 data-jump 指向目标 id；目标被 hidden 时整项收起，
 *    这样未登录时不会留下指向「紧急联系人」的死链。
 */
(function () {
  var shell = document.getElementById("shell");
  if (!shell) return;

  var COLLAPSE_KEY = "sm-nav-collapsed";
  var toggle = document.getElementById("navToggle");
  var openBtn = document.getElementById("navOpen");
  var veil = document.getElementById("navVeil");
  var mobile = window.matchMedia("(max-width: 900px)");

  function stored() {
    try { return localStorage.getItem(COLLAPSE_KEY) === "1"; } catch (e) { return false; }
  }

  function setCollapsed(collapsed, remember) {
    shell.classList.toggle("is-collapsed", collapsed);
    if (toggle) {
      toggle.setAttribute("aria-expanded", String(!collapsed));
      toggle.title = collapsed ? "展开侧栏" : "收起侧栏";
      toggle.setAttribute("aria-label", toggle.title);
    }
    if (remember) {
      try { localStorage.setItem(COLLAPSE_KEY, collapsed ? "1" : "0"); } catch (e) { /* 隐私模式 */ }
    }
  }

  setCollapsed(stored(), false);

  if (toggle) {
    toggle.addEventListener("click", function () {
      // 小屏时这个按钮就是「关闭抽屉」，折叠宽度在抽屉里没有意义
      if (mobile.matches) { closeDrawer(); return; }
      setCollapsed(!shell.classList.contains("is-collapsed"), true);
    });
  }

  function openDrawer() {
    shell.classList.add("is-open");
    if (veil) veil.hidden = false;
    document.documentElement.style.overflow = "hidden";
  }

  function closeDrawer() {
    shell.classList.remove("is-open");
    if (veil) veil.hidden = true;
    document.documentElement.style.overflow = "";
  }

  if (openBtn) openBtn.addEventListener("click", openDrawer);
  if (veil) veil.addEventListener("click", closeDrawer);
  document.addEventListener("keydown", function (e) {
    if (e.key === "Escape") closeDrawer();
  });
  mobile.addEventListener("change", closeDrawer);

  /* ---------- 页内锚点 ---------- */
  var jumps = Array.prototype.slice.call(document.querySelectorAll("[data-jump]"));
  var jumpGroup = document.getElementById("jumpGroup");

  function jumpTarget(link) {
    return document.getElementById(link.getAttribute("data-jump"));
  }

  function syncJumps() {
    var any = false;
    jumps.forEach(function (link) {
      var target = jumpTarget(link);
      var visible = !!target && !target.hidden && target.offsetParent !== null;
      link.hidden = !visible;
      if (visible) any = true;
    });
    if (jumpGroup) jumpGroup.hidden = !any;
  }

  syncJumps();

  if (window.MutationObserver) {
    var pending;
    var observer = new MutationObserver(function () {
      clearTimeout(pending);
      pending = setTimeout(function () { syncJumps(); spy(); }, 60);
    });
    observer.observe(document.body, { subtree: true, attributes: true, attributeFilter: ["hidden"] });
  }

  jumps.forEach(function (link) {
    if (link.getAttribute("href")) {
      link.addEventListener("click", closeDrawer);
    }
  });

  /* 当前读到哪一节，就在侧栏里点亮哪一项 */
  function spy() {
    var line = window.scrollY + 140;
    var current = null;
    jumps.forEach(function (link) {
      var target = jumpTarget(link);
      if (!target || link.hidden) return;
      var top = target.getBoundingClientRect().top + window.scrollY;
      if (top <= line) current = link;
    });
    jumps.forEach(function (link) {
      link.classList.toggle("is-on", link === current);
    });
  }

  window.addEventListener("scroll", spy, { passive: true });
  window.addEventListener("resize", spy);
  spy();

  /* ---------- 卡片折叠 ---------- */
  var FOLD_PREFIX = "sm-fold-";

  function foldKey(card) {
    return FOLD_PREFIX + (card.id || "");
  }

  function readFold(card) {
    try {
      var saved = localStorage.getItem(foldKey(card));
      if (saved === "1") return true;
      if (saved === "0") return false;
    } catch (e) { /* 隐私模式 */ }
    return card.getAttribute("data-folded") === "true";
  }

  Array.prototype.forEach.call(document.querySelectorAll("[data-collapse]"), function (card) {
    var button = card.querySelector(".card-fold");
    var collapsed = readFold(card);
    card.classList.toggle("is-folded", collapsed);
    if (button) button.setAttribute("aria-expanded", String(!collapsed));

    function flip() {
      var next = !card.classList.contains("is-folded");
      card.classList.toggle("is-folded", next);
      if (button) button.setAttribute("aria-expanded", String(!next));
      try { localStorage.setItem(foldKey(card), next ? "1" : "0"); } catch (e) { /* 隐私模式 */ }
    }

    if (button) {
      button.addEventListener("click", function (e) {
        e.stopPropagation();
        flip();
      });
    }
  });
})();
