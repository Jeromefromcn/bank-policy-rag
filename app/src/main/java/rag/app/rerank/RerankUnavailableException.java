package rag.app.rerank;

/** The reranker could not be reached or returned an unusable response. Maps to HTTP 503. */
public class RerankUnavailableException extends RuntimeException {

    public RerankUnavailableException(String message) {
        super(message);
    }

    public RerankUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
