package com.socraticmind.app.web;

import com.socraticmind.app.review.KnowledgeReviewStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库文档审核记录的只读接口（内部令牌保护）。
 *
 * 只读是刻意的：审核记录不能被内部接口批量删除或改写，
 * 否则"这条不好看，删掉"会变成一种默认操作。
 */
@RestController
@RequestMapping("/api/internal/knowledge-reviews")
public class InternalKnowledgeReviewController {

    private final KnowledgeReviewStore store;

    public InternalKnowledgeReviewController(KnowledgeReviewStore store) {
        this.store = store;
    }

    @GetMapping
    public List<KnowledgeReviewStore.DocReview> list(@RequestParam(required = false) String documentId) {
        return documentId == null || documentId.isBlank()
                ? store.all()
                : store.forDocument(documentId);
    }
}
