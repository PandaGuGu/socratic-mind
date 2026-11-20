const API = "/api/knowledge";

const SUGGESTIONS = [
  "焦虑症有哪些常见症状？",
  "抑郁症的治疗方法有哪些？",
  "如何预防自杀？",
  "哪些因素会影响心理健康？",
  "出现自杀念头时应该找谁帮忙？"
];

const $ = (id) => document.getElementById(id);

function esc(value) {
  return String(value == null ? "" : value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

async function postJson(url, body) {
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(body)
  });
  if (!res.ok) {
    let detail = "";
    try { detail = await res.text(); } catch (e) { /* 忽略 */ }
    throw new Error(`${res.status} ${res.statusText}${detail ? " · " + detail.slice(0, 120) : ""}`);
  }
  return res.json();
}

function renderSuggestions() {
  $("suggestions").innerHTML = SUGGESTIONS.map((q) =>
    `<button class="suggestion" type="button" data-q="${esc(q)}">${esc(q)}</button>`
  ).join("");

  $("suggestions").querySelectorAll("[data-q]").forEach((btn) => {
    btn.addEventListener("click", () => {
      $("question").value = btn.dataset.q;
      ask(btn.dataset.q);
    });
  });
}

function setLoading() {
  $("answerArea").innerHTML = `
    <div class="loading">
      <p>检索资料中</p>
      <div class="bar"></div>
    </div>`;
  $("sourceCount").textContent = "0";
  $("sourceList").innerHTML = `<p class="empty">正在检索出处……</p>`;
}

function formatAnswer(text) {
  return esc(text).replace(/\[(\d+)\]/g,
    '<sup class="ref" data-ref="$1" role="button" tabindex="0">$1</sup>');
}

function renderSources(citations) {
  const list = citations || [];
  $("sourceCount").textContent = String(list.length);

  if (!list.length) {
    $("sourceList").innerHTML = `<p class="empty">这条回答没有可列出的出处。</p>`;
    return;
  }

  $("sourceList").innerHTML = list.map((c) => {
    const meta = [c.source, c.section, c.page ? `P.${c.page}` : ""].filter(Boolean).map(esc).join(" · ");
    const link = c.sourceUrl
      ? `<a class="source-link" href="${esc(c.sourceUrl)}" target="_blank" rel="noopener noreferrer">查看原文</a>`
      : "";
    return `
      <div class="source-card" id="source-${c.number}">
        <div class="source-no">[${c.number}]</div>
        <div class="source-title">${esc(c.documentTitle || "未命名资料")}</div>
        <div class="source-meta">${meta || "未标注来源"}</div>
        ${link}
      </div>`;
  }).join("");
}

function renderAnswer(result) {
  const insufficient = result.status === "insufficient_evidence";
  const citations = result.citations || [];
  const generatedBy = String(result.generatedBy || "");
  const modelNote = generatedBy.startsWith("stub")
    ? "开发预览 · 桩输出"
    : (generatedBy ? generatedBy.replace(/^http:/, "模型 ") : "");

  if (insufficient) {
    $("answerArea").innerHTML = `
      <div class="insufficient">
        <b>现有资料不足以回答这一点。</b>
        <p>${esc(result.answer || "知识库里没有能支持这个结论的资料。")}</p>
      </div>`;
    renderSources([]);
    return;
  }

  const invalid = result.verification && result.verification.allCitationsValid === false;
  const note = invalid
    ? "引用校验未完全通过，请以原文为准。"
    : (citations.length ? "每条引用都可以在右侧出处里核对。" : "这条回答没有标注出处。");

  $("answerArea").innerHTML = `
    <article class="answer-card">
      <div class="answer-head">
        <i></i>
        <span>来自知识库的回答</span>
        ${modelNote ? `<span class="model">${esc(modelNote)}</span>` : ""}
      </div>
      <div class="answer">${formatAnswer(result.answer || "")}</div>
      <div class="answer-note">${esc(note)}</div>
    </article>`;

  renderSources(citations);

  $("answerArea").querySelectorAll("sup.ref").forEach((ref) => {
    const jump = () => {
      const target = document.getElementById(`source-${ref.dataset.ref}`);
      if (!target) return;
      target.scrollIntoView({ block: "center", behavior: "smooth" });
      target.classList.add("is-on");
      setTimeout(() => target.classList.remove("is-on"), 1800);
    };
    ref.addEventListener("click", jump);
    ref.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") { e.preventDefault(); jump(); }
    });
  });
}

async function ask(question) {
  const q = String(question || "").trim();
  if (!q) return;

  $("question").value = q;
  const button = document.querySelector(".ask .btn-primary");
  button.disabled = true;
  setLoading();

  try {
    const result = await postJson(`${API}/chat`, { message: q });
    renderAnswer(result);
    const url = new URL(location.href);
    url.searchParams.set("q", q);
    history.replaceState(null, "", url);
  } catch (e) {
    $("answerArea").innerHTML = `
      <div class="insufficient">
        <b>暂时读不到知识库。</b>
        <p>请稍后再试。</p>
      </div>`;
    renderSources([]);
  } finally {
    button.disabled = false;
  }
}

$("askForm").addEventListener("submit", (e) => {
  e.preventDefault();
  ask($("question").value);
});

renderSuggestions();

const params = new URLSearchParams(location.search);
const initial = params.get("q");
if (initial) {
  ask(initial);
} else {
  $("question").focus();
}
