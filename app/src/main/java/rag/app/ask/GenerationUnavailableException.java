package rag.app.ask;

/** The generation model could not be reached or returned an error. Maps to HTTP 503. */
public class GenerationUnavailableException extends RuntimeException {

    public GenerationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
