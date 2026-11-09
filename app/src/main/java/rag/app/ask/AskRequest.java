package rag.app.ask;

/**
 * {@code POST /v1/ask} body. {@code conversationId} null starts a new conversation; otherwise it must be an id this
 * caller received earlier, or the request is a 404 (spec 04 §6.1).
 */
public record AskRequest(String question, Integer topK, String mode, Boolean rerank, String conversationId) {}
