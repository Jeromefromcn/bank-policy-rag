package rag.app.embed;

/** The embedding backend could not be reached or returned an error. Maps to HTTP 503. */
public class EmbeddingUnavailableException extends RuntimeException {

    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
