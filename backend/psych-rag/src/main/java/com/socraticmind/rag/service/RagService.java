package com.socraticmind.rag.service;

import com.socraticmind.common.guard.PolicyProfiles;
import com.socraticmind.common.model.Verification;
import com.socraticmind.common.port.ChatPort;
import com.socraticmind.common.port.KnowledgeStore;
import com.socraticmind.common.prompt.PromptSettings;
import com.socraticmind.common.support.TextUtils;
import com.socraticmind.rag.dto.EvidencePack;
import com.socraticmind.rag.dto.Hit;
import com.socraticmind.rag.dto.PackItem;
import com.socraticmind.rag.evidence.EvidenceAssembler;
import com.socraticmind.rag.retrieval.HybridRetriever;
import com.socraticmind.rag.retrieval.RerankingRetriever;
import com.socraticmind.rag.validation.CitationVerifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 门面：检索、证据组装、生成、校验。
 * 只有"证据"这件事归它管；流程（第几步、要不要追问）归 agent 层，见 ADR-0005。
 */
@Service
public class RagService {

    private final RerankingRetriever retriever;
    private final EvidenceAssembler assembler;
    private final CitationVerifier verifier;
    private final ChatPort chat;
    private final PromptSettings prompts;

    private static final String PROMPT_FALLBACK =
            "只根据给出的证据回答。证据不足时明确说资料不足，不要编造；引用编号必须来自证据。";

    public RagService(RerankingRetriever retriever, EvidenceAssembler assembler,
                      CitationVerifier verifier, ChatPort chat, KnowledgeStore store,
                      PromptSettings prompts) {
        this.retriever = retriever;
        this.assembler = assembler;
        this.verifier = verifier;
        this.chat = chat;
        this.prompts = prompts;
    }

    public List<Hit> retrieve(String query, int topK) {
        return retriever.retrieve(query, topK);
    }

    /** 带语料范围的检索：null = 不限制，空集合 = 无可见文档（必然检索不到）。 */
    public List<Hit> retrieve(String query, int topK, java.util.Set<String> scopes) {
        return retriever.retrieve(query, topK, scopes);
    }

    public String rerankModelId() {
        return retriever.modelId();
    }

    public EvidencePack assemble(String query, List<Hit> hits) {
        return assembler.assemble(query, hits);
    }

    public String generate(String question, EvidencePack pack) {
        // 带档位：知识问答在输出策略里是独立的一档，不能和房间内/门外共用。
        String prompt = prompts == null ? "" : prompts.get().knowledgeAnswer();
        return chat.chat(prompt == null || prompt.isBlank() ? PROMPT_FALLBACK : prompt,
                buildUserPrompt(question, pack), PolicyProfiles.RAG_ANSWER);
    }

    public Verification verify(String answer, EvidencePack pack) {
        return verifier.verify(answer, pack);
    }

    public java.util.Set<Integer> referenced(String answer) {
        return verifier.referenced(answer);
    }

    public String modelId() {
        return chat.modelId();
    }

    /** 证据块的文本格式是契约：桩生成器与真实模型看到的是同一份。改动要同步 StubChatAdapter。 */
    String buildUserPrompt(String question, EvidencePack pack) {
        StringBuilder sb = new StringBuilder("证据：\n");
        for (PackItem item : pack.items()) {
            sb.append('[').append(item.citation().number()).append("] ")
                    .append(item.citation().display()).append('\n')
                    .append(item.chunk().content().trim()).append("\n\n");
        }
        sb.append("问题：").append(question).append('\n');
        return sb.toString();
    }

    public String preview(String query) {
        List<Hit> hits = retrieve(query, 4);
        return TextUtils.truncate(hits.isEmpty() ? "" : hits.get(0).chunk().content(), 80);
    }
}
