package rag.app.ask;

/** {@code POST /v1/ask} body. {@code conversationId} is reserved for Phase 4 and must be null. */
public record AskRequest(String question, Integer topK, String mode, Boolean rerank, String conversationId) {}
