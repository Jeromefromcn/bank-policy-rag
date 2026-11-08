package rag.core.chunk;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/** Counts cl100k_base tokens. Stable and comparable; not the embedding model's tokenizer (ROADMAP §4.4). */
public final class TokenCounter {

    private final Encoding encoding;

    private TokenCounter(Encoding encoding) {
        this.encoding = encoding;
    }

    public static TokenCounter cl100k() {
        return new TokenCounter(Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE));
    }

    public int count(String text) {
        return encoding.countTokensOrdinary(text);
    }
}
