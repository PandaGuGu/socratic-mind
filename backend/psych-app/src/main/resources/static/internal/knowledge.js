const API = "/api/knowledge";

const STATUS = {
  published: { label: "已发布", tag: "is-model", dot: "is-ok" },
  reviewing: { label: "待审核", tag: "is-template", dot: "is-wait" },
  draft: { label: "草稿", tag: "", dot: "" },
  archived: { label: "已归档", tag: "", dot: "" }
};

const S = {
  stats: null,
  docs: [],
  selectedId: null,
  filter: "all",
  tab: "search",
  chunks: null,
  chunksFor: null,
  highlight: null,
  search: null,
  chat: null,
  ingest: null
};

const $ = (id) => document.getElementById(id);

function esc(value) {
  return String(value == null ? "" : value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function statusOf(status) {
  return STATUS[status] || { label: status || "未知", tag: "", dot: "" };
}

function dateOf(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return d.toLocaleString("zh-CN", { hour12: false, month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
}

async function getJson(url) {
  const res = await fetch(url, { headers: { Accept: "application/json" } });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.json();
}

async function postJson(url, body) {
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(body)
  });
  if (!res.ok) {
    let detail = "";
    try { detail = await res.text(); } catch (e) { /* 忽略读取失败 */ }
    throw new Error(`${res.status} ${res.statusText}${detail ? " · " + detail.slice(0, 140) : ""}`);
  }
  return res.json();
}

async function putJson(url, body) {
  const res = await fetch(url, {
    method: "PUT",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(body)
  });
  if (!res.ok) {
    let detail = "";
    try { detail = await res.text(); } catch (e) { /* 忽略读取失败 */ }
    throw new Error(`${res.status} ${res.statusText}${detail ? " · " + detail.slice(0, 140) : ""}`);
  }
  return res.json();
}

function toast(message, isError) {
  const el = $("toast");
  el.textContent = message;
  el.hidden = false;
  el.classList.toggle("is-error", Boolean(isError));
  clearTimeout(toast._timer);
  toast._timer = setTimeout(() => { el.hidden = true; }, 2800);
}

/* ---------------- 顶部状态 ---------------- */

function renderBadges() {
  const st = S.stats || {};
  const badges = [
    `<span class="badge"><i></i>${st.documents || 0} 份资料</span>`,
    `<span class="badge is-ok"><i></i>已发布 ${st.published || 0}</span>`,
    `<span class="badge is-warn"><i></i>待审核 ${st.reviewing || 0}</span>`,
    `<span class="badge"><i></i>${st.chunks || 0} 个可检索块</span>`
  ];
  if (st.store === "memory") {
    badges.push(`<span class="badge is-warn"><i></i>知识库在内存 · 重启需重灌</span>`);
  } else if (st.store) {
    badges.push(`<span class="badge is-quiet"><i></i>存储 ${esc(st.store)}</span>`);
  }
  $("badges").innerHTML = badges.join("");
  $("storeNote").textContent = st.store === "memory"
    ? "知识库为内存实现：重启后只剩随包示例语料，新增资料需重新入库。"
    : `知识库存储：${st.store || "—"}。只有「已发布」的资料会进入检索候选集。`;
}

/* ---------------- 资料列表 ---------------- */

function filteredDocs() {
  return S.filter === "all" ? S.docs : S.docs.filter((d) => d.status === S.filter);
}

function renderDocs() {
  $("docCount").textContent = String(S.docs.length);
  const rows = filteredDocs();
  if (!S.docs.length) {
    $("docList").innerHTML = `<p class="kb-hint" style="padding:10px 6px">知识库里还没有资料。到「新增资料」贴一段 Markdown 试试。</p>`;
    return;
  }
  if (!rows.length) {
    $("docList").innerHTML = `<p class="kb-hint" style="padding:10px 6px">这个筛选下没有资料。</p>`;
    return;
  }
  $("docList").innerHTML = rows.map((d) => {
    const s = statusOf(d.status);
    return `
      <div class="session-item doc-item ${S.selectedId === d.id ? "is-on" : ""}"
           data-select="${esc(d.id)}" role="button" tabindex="0">
        <span class="session-top">
          <span class="session-user">${esc(d.title)}</span>
          <span class="status"><i class="${s.dot}"></i>${esc(s.label)}</span>
        </span>
        <span class="session-meta">
          <span class="session-id">${esc(d.id)}</span>
          <span style="margin-left:auto">${dateOf(d.createdAt)}</span>
        </span>
      </div>`;
  }).join("");

  $("docList").querySelectorAll("[data-select]").forEach((el) => {
    const pick = () => selectDoc(el.dataset.select);
    el.addEventListener("click", pick);
    el.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") { e.preventDefault(); pick(); }
    });
  });
}

function currentDoc() {
  return S.docs.find((d) => d.id === S.selectedId) || null;
}

function renderMeta() {
  const d = currentDoc();
  $("meta").innerHTML = d
    ? `<span>${esc(d.title)}</span><span>· ${esc(statusOf(d.status).label)}</span>`
    : `<span>未选择资料</span>`;
}

async function selectDoc(id) {
  S.selectedId = id;
  S.highlight = null;
  if (S.tab === "detail") await ensureChunks(id);
  renderDocs();
  renderMeta();
  renderBody();
}

async function openDoc(id, chunkId) {
  S.tab = "detail";
  syncTabs();
  S.selectedId = id;
  S.highlight = chunkId || null;
  await ensureChunks(id);
  renderDocs();
  renderMeta();
  renderBody();
}

async function ensureChunks(id, force) {
  if (!force && S.chunksFor === id && S.chunks) return;
  S.chunks = null;
  S.chunksFor = id;
  try {
    S.chunks = await getJson(`${API}/documents/${encodeURIComponent(id)}/chunks`);
  } catch (e) {
    S.chunks = [];
    toast(`读取切分失败：${e.message}`, true);
  }
}

/* ---------------- 主区域 ---------------- */

function syncTabs() {
  document.querySelectorAll("#tabs .tab").forEach((t) => {
    t.classList.toggle("is-on", t.dataset.tab === S.tab);
  });
}

function renderBody() {
  if (S.tab === "search") {
    $("body").innerHTML = searchView();
  } else if (S.tab === "chat") {
    $("body").innerHTML = chatView();
  } else if (S.tab === "detail") {
    $("body").innerHTML = detailView();
  } else {
    $("body").innerHTML = ingestView();
  }
  bindBody();
  if (S.tab === "detail" && S.highlight) {
    const el = document.getElementById(`chunk-${S.highlight}`);
    if (el) el.scrollIntoView({ block: "center", behavior: "smooth" });
  }
}

function viaLabel(via) {
  if (via === "both") return "双路";
  if (via === "text") return "全文";
  if (via === "vector") return "向量";
  return via || "—";
}

function searchView() {
  const q = S.search ? S.search.query : "";
  const k = S.search ? S.search.topK : 5;
  const options = [3, 5, 8, 10].map((n) => `<option value="${n}" ${n === k ? "selected" : ""}>Top ${n}</option>`).join("");
  return `
    <form class="kb-panel" id="searchForm">
      <h3>检索调试</h3>
      <p class="kb-hint">只看召回，不调用生成。命中项可直接跳到对应资料与切分块。</p>
      <div class="kb-form">
        <input class="kb-input grow" id="searchQ" value="${esc(q)}" placeholder="例如：自动思维有什么特征？">
        <select class="kb-select" id="searchK" style="width:96px">${options}</select>
        <button class="btn btn-primary" type="submit">检索</button>
      </div>
    </form>
    ${searchResultView()}`;
}

function searchResultView() {
  if (!S.search) {
    return `<p class="kb-hint">输入一个问题，看分类、改写、两路命中与融合排序。这里不消耗模型额度。</p>`;
  }
  const r = S.search.result;
  const hits = r.hits || [];
  return `
    <div class="kb-panel">
      <div class="kb-actions" style="margin-top:0">
        <span class="tag">分类 ${esc(r.classifiedAs || "—")}</span>
        <span class="tag is-plain">改写：${esc(r.rewrittenQuery || "—")}</span>
      </div>
      ${hits.length ? `<div class="hits" style="margin-top:10px">${hits.map(hitRow).join("")}</div>`
        : `<div class="notice" style="margin-top:10px">没有任何命中。检索不到不是异常，它就是「资料不足」的正常输入。</div>`}
    </div>`;
}

function hitRow(h, index) {
  const tagClass = h.via === "both" ? "is-model" : h.via === "text" ? "is-template" : "";
  return `
    <div class="hit" data-doc="${esc(h.documentId)}" data-chunk="${esc(h.chunkId)}" role="button" tabindex="0">
      <span class="rank">${index + 1}</span>
      <span class="where">${esc(h.documentTitle)}<small>${esc(h.section || "（无章节）")}</small></span>
      <span class="tag ${tagClass}">${viaLabel(h.via)}</span>
      <span class="score">${Number(h.score || 0).toFixed(4)}<small>v ${Number(h.vectorScore || 0).toFixed(2)} · t ${Number(h.textScore || 0).toFixed(2)}</small></span>
      <span class="preview">${esc(h.preview)}</span>
    </div>`;
}

function chatView() {
  const question = S.chat ? S.chat.question : "";
  return `
    <form class="kb-panel" id="chatForm">
      <h3>问答试跑</h3>
      <p class="kb-hint">走完整链路：分类 → 改写 → 检索 → 证据评估 → 生成 → 引用校验。证据不足会明确拒答。</p>
      <div class="kb-field" style="margin-top:10px">
        <textarea class="kb-textarea" id="chatQ" style="min-height:74px" placeholder="例如：回避为什么会让焦虑持续？">${esc(question)}</textarea>
      </div>
      <div class="kb-actions">
        <button class="btn btn-primary" type="submit">提问</button>
        <span class="kb-hint">当前生成方由服务端配置决定；桩模型不会产生真实语义质量。</span>
      </div>
    </form>
    ${chatResultView()}`;
}

function chatResultView() {
  if (!S.chat) return "";
  const r = S.chat.result;
  const insufficient = r.status === "insufficient_evidence";
  const citations = r.citations || [];
  const trace = r.trace || [];
  const v = r.verification || {};
  const invalid = v.invalidCitationNums || [];
  const unsupported = v.unsupportedClaims || [];
  const coverage = Math.round(Number(v.citationCoverage || 0) * 100);

  return `
    <div class="kb-panel">
      <div class="kb-actions" style="margin-top:0">
        <span class="tag ${insufficient ? "is-template" : "is-model"}">${insufficient ? "资料不足" : "已回答"}</span>
        <span class="tag">生成方 ${esc(r.generatedBy || "—")}</span>
        ${r.generatedBy && String(r.generatedBy).indexOf("stub") === 0
          ? `<span class="tag is-template">桩输出 · 不代表语义质量</span>` : ""}
      </div>
      <div class="answer" style="margin-top:10px">${esc(r.answer || "（空回答）")}</div>

      <p class="section-label" style="margin-top:16px">引用 · ${citations.length} 条</p>
      ${citations.length ? citations.map((c) => `
        <div class="cite" data-doc="${esc(c.documentId)}" data-chunk="${esc(c.chunkId)}" role="button" tabindex="0">
          <span class="no">[${c.number}]</span>
          <span>${esc(c.documentTitle)}${c.section ? " · " + esc(c.section) : ""}${c.page ? " · P." + c.page : ""}</span>
        </div>`).join("")
        : `<p class="kb-hint">这条回答没有引用。拒答时属于正常结果。</p>`}

      <p class="section-label" style="margin-top:16px">校验</p>
      <div class="kb-actions" style="margin-top:0">
        <span class="tag ${v.allCitationsValid ? "is-model" : "is-refer"}">${v.allCitationsValid ? "引用编号有效" : "存在无效编号"}</span>
        <span class="tag">覆盖率 ${coverage}%</span>
        ${invalid.length ? `<span class="tag is-refer">无效 [${invalid.join(", ")}]</span>` : ""}
        ${unsupported.length ? `<span class="tag is-template">未支撑陈述 ${unsupported.length} 条</span>` : ""}
      </div>
      ${unsupported.length ? `<div class="notice" style="margin-top:10px">${unsupported.map((s) => "· " + esc(s)).join("<br>")}</div>` : ""}
    </div>

    <div class="kb-panel">
      <h3>节点轨迹</h3>
      ${trace.length ? `<ol class="trace" style="margin-top:8px">${trace.map((t) => `<li>${esc(t)}</li>`).join("")}</ol>`
        : `<p class="kb-hint" style="margin-top:8px">没有轨迹。</p>`}
    </div>`;
}

function detailView() {
  const d = currentDoc();
  if (!d) {
    return `<div class="empty"><h3>还没有选资料</h3><p>从左侧选一份，查看它的状态、切分与审核动作。</p></div>`;
  }
  const s = statusOf(d.status);
  // "归档"与"上游删除"在数据上都是 archived，但对运营是两件事：
  // 前者是我们撤的，后者是同步任务发现源文档没了。混在一起会让人去点错按钮。
  const statusLabel = d.deletedAt ? "已删除（上游）" : s.label;
  const acl = d.aclScope ? d.aclScope : "所有人可见";
  const loaded = S.chunksFor === d.id ? S.chunks : null;
  return `
    <div class="doc-meta">
      <div class="meta-cell"><dt>状态</dt><dd>${esc(statusLabel)}</dd></div>
      <div class="meta-cell"><dt>集合</dt><dd>${esc(d.collection || "—")}</dd></div>
      <div class="meta-cell"><dt>来源</dt><dd>${esc(d.source || "—")}</dd></div>
      <div class="meta-cell"><dt>版本</dt><dd>${esc(d.version || "—")}</dd></div>
      <div class="meta-cell"><dt>可见范围</dt><dd>${esc(acl)}</dd></div>
      <div class="meta-cell"><dt>入库时间</dt><dd>${dateOf(d.createdAt)}</dd></div>
      <div class="meta-cell"><dt>外部键</dt><dd>${esc(d.externalKey || "—")}</dd></div>
      <div class="meta-cell"><dt>内容指纹</dt><dd>${d.contentHash ? esc(d.contentHash.slice(0, 12)) + "…" : "—"}</dd></div>
      ${d.deletedAt ? `<div class="meta-cell"><dt>删除时间</dt><dd>${dateOf(d.deletedAt)}</dd></div>` : ""}
      <div class="meta-cell" style="grid-column:1 / -1"><dt>文档 ID</dt><dd>${esc(d.id)}</dd></div>
      ${d.sourceUrl ? `<div class="meta-cell" style="grid-column:1 / -1"><dt>来源链接</dt><dd>${esc(d.sourceUrl)}</dd></div>` : ""}
    </div>
    ${reviewActions(d)}
    ${reviewPanel(d)}
    <p class="section-label" style="margin-top:16px">切分预览 · ${loaded ? loaded.length + " 块" : "读取中"}</p>
    ${loaded ? (loaded.length ? loaded.map(chunkCard).join("") : `<div class="notice">这份资料没有可展示的块。</div>`)
      : `<p class="kb-hint">读取切分…</p>`}`;
}

/**
 * 审核与可见范围。
 *
 * 三个字段都是"签字时必须看见"的东西：谁签、签的哪一版、当时给谁看。
 * 可见范围做成可编辑而不是只读，是因为它低频但必须能改；做成输入框而不是下拉，
 * 是因为这里没有组织架构数据，编一套假的角色列表比留空更糟。
 */
function reviewPanel(d) {
  const acl = d.aclScope ? d.aclScope : "";
  return `<div class="kb-panel" style="margin-top:14px">
    <h3>审核与可见范围</h3>
    <p class="kb-hint">签字针对版本：当前 ${esc(d.version || "—")}。内容一变，旧签字不再生效，需要重新审。</p>
    <div class="kb-grid" style="margin-top:10px">
      <div class="kb-field"><label>签字人（来自登录身份，不可编辑）</label>
        <input class="kb-input" id="kbReviewer" readonly value="读取中…"></div>
      <div class="kb-field"><label>可见范围（逗号分隔，空 = 公开）</label>
        <input class="kb-input" id="kbAcl" value="${esc(acl)}" placeholder="team:cbt, role:clinician"></div>
    </div>
    <div class="kb-actions">
      <button class="btn" id="kbSaveAcl" type="button">保存可见范围</button>
      <span class="kb-hint" id="kbAclHint">当前：${esc(d.aclScope || "所有人可见")}</span>
    </div>
    <p class="section-label" style="margin-top:14px">审核记录</p>
    <div id="kbReviewHistory"><p class="kb-hint">读取中…</p></div>
  </div>`;
}

/**
 * 显示当前登录身份。
 *
 * 这个名字不能让人手填：审核记录里的署名来自会话/令牌，
 * 让界面再给一个输入框，只会造成"我填了李医生、系统记的是张医生"。
 */
async function loadIdentity() {
  const el = $("kbReviewer");
  if (!el) return;
  try {
    const me = await getJson("/api/operator/me");
    const u = me.user || {};
    el.value = `${u.name || "未知"}（${u.role || "-"}）`;
  } catch (err) {
    el.value = "未登录：只能查看，不能签字";
  }
}

async function saveAcl() {
  const d = currentDoc();
  if (!d) return;
  const raw = $("kbAcl").value || "";
  const scopes = raw.split(/[,;\s]+/).map((s) => s.trim()).filter(Boolean);
  const hint = $("kbAclHint");
  try {
    const out = await putJson(`${API}/documents/${encodeURIComponent(d.id)}/acl`, { scopes });
    hint.textContent = "当前：" + (out.aclScope || "所有人可见");
    toast(out.aclScope ? `可见范围已改为 ${out.aclScope}` : "已改为所有人可见");
    await refresh(false);
  } catch (err) {
    hint.textContent = "保存失败：" + err.message;
  }
}

async function loadReviewHistory(docId) {
  const box = $("kbReviewHistory");
  if (!box) return;
  try {
    const rows = await getJson(`/api/internal/knowledge-reviews?documentId=${encodeURIComponent(docId)}`);
    if (!rows.length) {
      box.innerHTML = `<p class="kb-hint">还没有登记过审核。发布前应当有人签过这一版。</p>`;
      return;
    }
    box.innerHTML = rows.map((r) => `
      <div class="notice" style="margin-top:6px">
        <b>${esc(r.version)}</b> · ${esc(REVIEW_LABEL[r.decision] || r.decision)}
        · ${esc(r.reviewer || "未署名")} · ${esc(dateOf(r.reviewedAt))}
        · 签字时可见范围：${esc(r.aclScope || "所有人可见")}
        ${r.note ? `<div class="kb-hint" style="margin-top:4px">${esc(r.note)}</div>` : ""}
      </div>`).join("");
  } catch (err) {
    box.innerHTML = `<p class="kb-hint">审核记录读取失败：${esc(err.message)}</p>`;
  }
}

const REVIEW_LABEL = { approve: "通过", reject: "退回", review: "转待审" };

function reviewActions(d) {
  const buttons = [];
  if (d.status !== "published") {
    buttons.push(`<button class="btn btn-primary" data-review="approve" type="button">通过并发布</button>`);
  }
  if (d.status !== "archived") {
    buttons.push(`<button class="btn" data-review="reject" type="button">退回归档</button>`);
  }
  if (d.status !== "reviewing") {
    buttons.push(`<button class="btn" data-review="review" type="button">标为待审核</button>`);
  }
  return `<div class="kb-actions">${buttons.join("")}
    <span class="kb-hint">发布后立即进入检索候选集；归档会把它撤出。</span></div>`;
}

function chunkCard(c) {
  const hit = c.id === S.highlight;
  return `
    <div class="chunk ${c.parent ? "is-parent" : ""} ${hit ? "is-hit" : ""}" id="chunk-${esc(c.id)}">
      <div class="chunk-head">
        <span>${c.parent ? "父块" : "子块 #" + c.index}</span>
        <span>${esc(c.sectionPath || "（无章节）")}</span>
        <span>${String(c.content || "").length} 字</span>
        <span style="margin-left:auto">${esc(c.id)}</span>
      </div>
      <div class="chunk-text">${esc(c.content)}</div>
    </div>`;
}

function ingestView() {
  return `
    <form class="kb-panel" id="ingestForm">
      <h3>新增资料</h3>
      <p class="kb-hint">只支持 Markdown / 纯文本。提交后进入「待审核」，审核通过才成为可引用证据。</p>
      <div class="kb-grid" style="margin-top:10px">
        <div class="kb-field"><label>标题 *</label><input class="kb-input" id="ingTitle" required></div>
        <div class="kb-field"><label>来源</label><input class="kb-input" id="ingSource" placeholder="机构 / 书名 / 出处说明"></div>
        <div class="kb-field"><label>来源链接</label><input class="kb-input" id="ingUrl" placeholder="https://…"></div>
        <div class="kb-field"><label>集合</label><input class="kb-input" id="ingCollection" value="default"></div>
      </div>
      <div class="kb-field" style="margin-top:10px">
        <label>正文（Markdown，建议用 # / ## 分节）*</label>
        <textarea class="kb-textarea" id="ingContent" required placeholder="# 标题&#10;&#10;## 章节&#10;&#10;正文……"></textarea>
      </div>
      <div class="kb-actions">
        <label class="kb-hint" style="display:flex;gap:6px;align-items:center">
          <input type="checkbox" id="ingPublish" checked> 提交审核（不会直接发布）
        </label>
        <button class="btn btn-primary" type="submit">入库</button>
        <span class="kb-hint">单条正文上限 20 万字。</span>
      </div>
    </form>
    ${S.ingest ? `<div class="kb-panel">
      <h3>上一次入库</h3>
      <div class="kb-actions" style="margin-top:6px">
        <span class="tag is-model">${esc(statusOf(S.ingest.status).label)}</span>
        <span class="tag">父块 ${S.ingest.parentChunks}</span>
        <span class="tag">子块 ${S.ingest.childChunks}</span>
        <span class="tag">证据 ${S.ingest.evidenceCount}</span>
        <span class="tag is-plain">${esc(S.ingest.documentId)}</span>
      </div>
    </div>` : ""}`;
}

/* ---------------- 事件 ---------------- */

function bindBody() {
  const searchForm = $("searchForm");
  if (searchForm) {
    searchForm.addEventListener("submit", async (e) => {
      e.preventDefault();
      const query = $("searchQ").value.trim();
      if (!query) return;
      const topK = Number($("searchK").value || 5);
      try {
        const result = await postJson(`${API}/search`, { query, topK });
        S.search = { query, topK, result };
        renderBody();
      } catch (err) {
        toast(`检索失败：${err.message}`, true);
      }
    });
  }

  const chatForm = $("chatForm");
  if (chatForm) {
    chatForm.addEventListener("submit", async (e) => {
      e.preventDefault();
      const question = $("chatQ").value.trim();
      if (!question) return;
      const btn = chatForm.querySelector("button[type=submit]");
      btn.disabled = true;
      btn.textContent = "生成中…";
      try {
        const result = await postJson(`${API}/chat`, { message: question });
        S.chat = { question, result };
        renderBody();
      } catch (err) {
        toast(`问答失败：${err.message}`, true);
        btn.disabled = false;
        btn.textContent = "提问";
      }
    });
  }

  const ingestForm = $("ingestForm");
  if (ingestForm) {
    ingestForm.addEventListener("submit", async (e) => {
      e.preventDefault();
      const title = $("ingTitle").value.trim();
      const content = $("ingContent").value.trim();
      if (!title || !content) return;
      const payload = {
        title,
        source: $("ingSource").value.trim(),
        sourceUrl: $("ingUrl").value.trim(),
        collection: $("ingCollection").value.trim() || "default",
        content,
        publish: $("ingPublish").checked
      };
      const btn = ingestForm.querySelector("button[type=submit]");
      btn.disabled = true;
      btn.textContent = "入库中…";
      try {
        const result = await postJson(`${API}/documents`, payload);
        S.ingest = result;
        S.selectedId = result.documentId;
        S.chunks = null;
        S.chunksFor = null;
        S.highlight = null;
        await refresh(false);
        S.tab = "detail";
        syncTabs();
        await ensureChunks(result.documentId, true);
        renderDocs();
        renderMeta();
        renderBody();
        toast(`已入库：${statusOf(result.status).label}`);
      } catch (err) {
        toast(`入库失败：${err.message}`, true);
        btn.disabled = false;
        btn.textContent = "入库";
      }
    });
  }

  document.querySelectorAll("[data-doc]").forEach((el) => {
    const open = () => openDoc(el.dataset.doc, el.dataset.chunk || null);
    el.addEventListener("click", open);
    el.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") { e.preventDefault(); open(); }
    });
  });

  document.querySelectorAll("[data-review]").forEach((btn) => {
    btn.addEventListener("click", () => review(btn.dataset.review));
  });

  const aclBtn = $("kbSaveAcl");
  if (aclBtn) aclBtn.addEventListener("click", saveAcl);
  if (S.tab === "detail" && currentDoc()) { loadReviewHistory(currentDoc().id); loadIdentity(); }
}

async function review(decision) {
  const d = currentDoc();
  if (!d) return;
  const label = { approve: "通过并发布", reject: "退回归档", review: "标为待审核" }[decision] || decision;
  // 不再传 reviewer：组织模式下署名只认会话/令牌，传了也会被后端忽略。
  if (!window.confirm(`确定对「${d.title}」v${d.version || "?"} 执行：${label}？署名取当前登录身份。`)) return;
  try {
    await postJson(`${API}/documents/${encodeURIComponent(d.id)}/review`, { decision });
    toast(`已${label}`);
    await refresh(false);
    if (S.tab === "detail") {
      await ensureChunks(d.id, true);
      renderBody();
    }
  } catch (err) {
    toast(`操作失败：${err.message}`, true);
  }
}

/* ---------------- 数据加载与启动 ---------------- */

async function loadStats() {
  S.stats = await getJson(`${API}/stats`);
}

async function loadDocs() {
  S.docs = await getJson(`${API}/documents`);
}

async function refresh(showToast) {
  await Promise.all([loadStats(), loadDocs()]);
  if (S.selectedId && !S.docs.some((d) => d.id === S.selectedId)) {
    S.selectedId = null;
    S.chunks = null;
    S.chunksFor = null;
  }
  renderBadges();
  renderDocs();
  renderMeta();
  renderBody();
  if (showToast) toast("已刷新");
}

async function boot() {
  try {
    const params = new URLSearchParams(location.search);
    const wantedTab = params.get("tab");
    if (["search", "chat", "detail", "ingest"].includes(wantedTab)) S.tab = wantedTab;
    syncTabs();
    await loadStats();
    await loadDocs();
    const wantedDoc = params.get("doc");
    if (wantedDoc && S.docs.some((d) => d.id === wantedDoc)) S.selectedId = wantedDoc;
    if (S.tab === "detail" && S.selectedId) await ensureChunks(S.selectedId);
    renderBadges();
    renderDocs();
    renderMeta();
    renderBody();
  } catch (e) {
    $("badges").innerHTML = `<span class="badge is-warn"><i></i>接口不可达</span>`;
    $("body").innerHTML = `
      <div class="empty">
        <h3>读不到知识库接口</h3>
        <p>${esc(e.message)}<br>确认应用已启动，并且 <code style="font-family:var(--mono)">psych.internal.enabled=true</code>。</p>
      </div>`;
  }
}

$("tabs").addEventListener("click", async (e) => {
  const btn = e.target.closest(".tab");
  if (!btn) return;
  S.tab = btn.dataset.tab;
  syncTabs();
  if (S.tab === "detail" && S.selectedId) await ensureChunks(S.selectedId);
  renderMeta();
  renderBody();
});

$("docFilters").addEventListener("click", (e) => {
  const btn = e.target.closest(".filter");
  if (!btn) return;
  S.filter = btn.dataset.status;
  $("docFilters").querySelectorAll(".filter").forEach((f) => f.classList.toggle("is-on", f === btn));
  renderDocs();
});

$("refreshBtn").addEventListener("click", async () => {
  try {
    await refresh(true);
  } catch (e) {
    toast(`刷新失败：${e.message}`, true);
  }
});

boot();
