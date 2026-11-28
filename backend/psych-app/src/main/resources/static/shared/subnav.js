/**
 * 页内子页签 + 侧栏子级联动（各模块复用同一套，和审核页一致）。
 *
 * 约定：
 * - 页签：.subnav .tab[data-sub="xxx"]，默认第一项；
 * - 内容块：[data-subpane="xxx"]，切换时 hidden；
 * - 侧栏：.sb-sub-item[data-sub="xxx"]，点它走 hash（#xxx），本脚本负责点亮。
 *
 * 页面不需要写任何 JS：只要带上 data-sub / data-subpane 和 data-sub 的侧栏项即可。
 * 地址栏保留 #xxx，刷新或回退还能回到同一个子页。
 */
(function () {
  var tabs = Array.prototype.slice.call(document.querySelectorAll(".subnav .tab[data-sub]"));
  if (!tabs.length) return;

  var panes = Array.prototype.slice.call(document.querySelectorAll("[data-subpane]"));
  var subs = Array.prototype.slice.call(document.querySelectorAll(".sb-sub-item[data-sub]"));
  var first = tabs[0].dataset.sub;

  function activate(name, push) {
    var known = tabs.filter(function (t) { return t.dataset.sub === name; }).length > 0;
    if (!known) name = first;
    tabs.forEach(function (t) {
      var on = t.dataset.sub === name;
      t.classList.toggle("is-on", on);
      t.setAttribute("aria-selected", String(on));
    });
    panes.forEach(function (p) {
      p.hidden = p.dataset.subpane !== name;
    });
    subs.forEach(function (a) {
      a.classList.toggle("is-on", a.dataset.sub === name);
    });
    try {
      var url = location.pathname + location.search + (name === first ? "" : "#" + name);
      if (push) history.pushState({ sub: name }, "", url);
      else history.replaceState({ sub: name }, "", url);
    } catch (e) { /* 嵌入环境可能不允许改历史记录 */ }
  }

  document.addEventListener("click", function (e) {
    var tab = e.target.closest(".subnav .tab[data-sub]");
    if (tab) activate(tab.dataset.sub, true);
  });
  window.addEventListener("hashchange", function () {
    activate(location.hash.replace("#", ""), false);
  });
  window.addEventListener("popstate", function () {
    activate(location.hash.replace("#", ""), false);
  });
  activate(location.hash.replace("#", ""), false);
})();
