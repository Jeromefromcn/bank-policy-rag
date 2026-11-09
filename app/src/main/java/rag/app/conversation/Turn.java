package rag.app.conversation;

/**
 * One stored turn of a conversation (spec 04 §6.1). A refused turn keeps its question and status, no answer.
 *
 * @param rewrittenQuery the standalone query used for retrieval; null on a first turn
 */
public record Turn(String question, String rewrittenQuery, String status, String answer) {}
