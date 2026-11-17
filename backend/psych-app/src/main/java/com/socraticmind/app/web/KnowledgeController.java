package com.socraticmind.app.web;

import com.socraticmind.agent.dto.SearchResult;
import com.socraticmind.agent.knowledge.KnowledgeFlow;
import com.socraticmind.common.model.KnowledgeDocument;
import com.socraticmind.common.model.RagAnswer;
import com.socraticmind.common.port.KnowledgeStore;
import com.socraticmind.knowledge.dto.IngestCommand;
import com.socraticmind.knowledge.dto.IngestResult;
import com.socraticmind.knowledge.service.IngestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeFlow flow;
    private final IngestionService ingestion;
    private final KnowledgeStore store;

    public KnowledgeController(KnowledgeFlow flow, IngestionService ingestion, KnowledgeStore store) {
        this.flow = flow;
        this.ingestion = ingestion;
        this.store = store;
    }

    public record ChatRequest(@NotBlank String message) {
    }

    public record SearchRequest(@NotBlank String query, Integer topK) {
    }

    public record UploadRequest(
            @NotBlank String title,
            String source,
            String sourceUrl,
            String collection,
            String content,
            Boolean publish
    ) {
    }

    public record ReviewRequest(@NotBlank String decision) {
    }

    @PostMapping("/chat")
    public RagAnswer chat(@Valid @RequestBody ChatRequest request) {
        return flow.run(request.message());
    }

    @PostMapping("/search")
    public SearchResult search(@Valid @RequestBody SearchRequest request) {
        return flow.search(request.query(), request.topK() == null ? 5 : request.topK());
    }

    @PostMapping("/documents")
    public ResponseEntity<IngestResult> upload(@Valid @RequestBody UploadRequest request) {
        if (request.content() == null || request.content().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        IngestCommand cmd = new IngestCommand(
                request.collection() == null ? "default" : request.collection(),
                request.title(), request.source(), request.sourceUrl(), "zh",
                Boolean.TRUE.equals(request.publish()), request.content());
        return ResponseEntity.ok(ingestion.ingest(cmd));
    }

    @PostMapping("/documents/{id}/review")
    public ResponseEntity<Map<String, Object>> review(@PathVariable String id,
                                                      @Valid @RequestBody ReviewRequest request) {
        KnowledgeDocument doc = store.findDocument(id).orElse(null);
        if (doc == null) {
            return ResponseEntity.notFound().build();
        }
        String decision = request.decision().toLowerCase();
        String status;
        switch (decision) {
            case "approve", "publish" -> status = KnowledgeDocument.PUBLISHED;
            case "reject", "archive" -> status = KnowledgeDocument.ARCHIVED;
            case "review" -> status = KnowledgeDocument.REVIEWING;
            default -> {
                return ResponseEntity.badRequest().body(Map.of("error", "decision 必须是 approve / reject / review"));
            }
        }
        store.updateDocumentStatus(id, status);
        return ResponseEntity.ok(Map.of("documentId", id, "status", status));
    }

    @GetMapping("/documents")
    public List<KnowledgeDocument> documents() {
        return store.findDocuments();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<KnowledgeDocument> docs = store.findDocuments();
        out.put("documents", docs.size());
        out.put("published", docs.stream().filter(d -> KnowledgeDocument.PUBLISHED.equals(d.status())).count());
        out.put("chunks", store.countChunks());
        out.put("store", "see psych.store");
        return out;
    }
}
