package rag.app.web;

import java.net.URI;

/** Stable RFC 9457 problem {@code type} URIs, one per error kind. */
public final class ProblemTypes {

    public static final URI UNAUTHORIZED = URI.create("urn:rag:problem:unauthorized");
    public static final URI FORBIDDEN = URI.create("urn:rag:problem:forbidden");
    public static final URI INVALID_REQUEST = URI.create("urn:rag:problem:invalid-request");
    public static final URI UNSUPPORTED_MODE = URI.create("urn:rag:problem:unsupported-mode");
    public static final URI NOT_FOUND = URI.create("urn:rag:problem:not-found");
    public static final URI EMBEDDING_UNAVAILABLE = URI.create("urn:rag:problem:embedding-unavailable");
    public static final URI GENERATION_UNAVAILABLE = URI.create("urn:rag:problem:generation-unavailable");
    public static final URI RERANKER_UNAVAILABLE = URI.create("urn:rag:problem:reranker-unavailable");
    public static final URI AUDIT_UNAVAILABLE = URI.create("urn:rag:problem:audit-unavailable");
    public static final URI AUDIT_READER_UNAVAILABLE = URI.create("urn:rag:problem:audit-reader-unavailable");

    private ProblemTypes() {}
}
