package rag.app.audit;

/** Why a request with no visible hits found nothing; audit and metrics only, never a response field. */
public enum InternalRefusalCause {
    NO_ACCESSIBLE_SOURCES,
    NO_MATCHING_CONTENT;

    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
