package rag.app.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import rag.app.embed.EmbeddingUnavailableException;

/** Maps errors to RFC 9457 problem details; Spring's own MVC errors keep their problem bodies. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ProblemDetail api(ApiException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        p.setType(e.type());
        return p;
    }

    @ExceptionHandler(EmbeddingUnavailableException.class)
    ProblemDetail embedding(EmbeddingUnavailableException e) {
        logger.warn("embedding unavailable", e);
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "The embedding service is unavailable");
        p.setType(ProblemTypes.EMBEDDING_UNAVAILABLE);
        return p;
    }
}
