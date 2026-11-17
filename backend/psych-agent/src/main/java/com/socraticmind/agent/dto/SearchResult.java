package com.socraticmind.agent.dto;

import java.util.List;

public record SearchResult(
        String query,
        String classifiedAs,
        String rewrittenQuery,
        List<SearchHit> hits
) {
}
