package rag.app.web;

import java.net.URI;
import org.springframework.http.HttpStatus;

/** A client-visible error with a stable problem type. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final URI type;

    public ApiException(HttpStatus status, URI type, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
    }

    public static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_REQUEST, detail);
    }

    public static ApiException unsupported(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.UNSUPPORTED_MODE, detail);
    }

    /** One body for "does not exist" and "exists but the caller may not see it". */
    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, "No such resource");
    }

    public HttpStatus status() {
        return status;
    }

    public URI type() {
        return type;
    }
}
