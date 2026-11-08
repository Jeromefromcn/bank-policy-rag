package rag.app.search;

import java.util.List;

public record SearchResponse(String queryId, List<SearchHit> hits) {}
