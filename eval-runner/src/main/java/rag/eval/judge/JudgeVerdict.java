package rag.eval.judge;

/** {@code ERROR} is a judge failure (HTTP, malformed or wrong-length output). It is never counted as supported. */
public enum JudgeVerdict {
    SUPPORTED,
    UNSUPPORTED,
    ERROR
}
