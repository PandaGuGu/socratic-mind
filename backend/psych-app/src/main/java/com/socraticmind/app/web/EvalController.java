package com.socraticmind.app.web;

import com.socraticmind.agent.knowledge.OnlineEvalService;
import com.socraticmind.common.model.BadCase;
import com.socraticmind.common.model.EvalSummary;
import com.socraticmind.common.model.QueryOutcome;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在线评测接口（内部令牌保护，见 InternalSecurityConfig 的 /api/internal/**）。
 *
 * 只读为主，唯一的写操作是把样本标记"已处理"或手工补一条——
 * 评测数据不能从内部接口批量删除，否则"这条太难看了，删掉"会变成一种默认操作。
 */
@RestController
@RequestMapping("/api/internal/eval")
public class EvalController {

    private final OnlineEvalService eval;

    public EvalController(OnlineEvalService eval) {
        this.eval = eval;
    }

    public record ReportRequest(String query, String answer, String note) {
    }

    @GetMapping("/summary")
    public EvalSummary summary(@RequestParam(defaultValue = "7") int days) {
        return eval.summarize(days);
    }

    @GetMapping("/badcases")
    public List<BadCase> badCases(@RequestParam(defaultValue = "true") boolean unresolved) {
        return eval.badCases(unresolved);
    }

    @GetMapping("/sample")
    public List<QueryOutcome> sample(@RequestParam(defaultValue = "7") int days,
                                     @RequestParam(defaultValue = "20") int n) {
        return eval.sample(days, n);
    }

    @PostMapping("/badcases/{id}/resolve")
    public ResponseEntity<Map<String, Object>> resolve(@PathVariable String id) {
        boolean ok = eval.resolve(id);
        if (!ok) return ResponseEntity.notFound().build();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("resolved", true);
        return ResponseEntity.ok(out);
    }

    /** 人工补样本：用户口头反馈、客服转来的问题都从这里进，来源记为 manual。 */
    @PostMapping("/badcases")
    public ResponseEntity<BadCase> report(@RequestBody ReportRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(eval.report(request.query(), request.answer(), request.note()));
    }
}
