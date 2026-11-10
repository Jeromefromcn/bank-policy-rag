package rag.core.chunk;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;

/** Counts cl100k_base tokens. Stable and comparable; not the embedding model's tokenizer (ROADMAP §4.4). */
public final class TokenCounter {

    /** Loaded once: the encoding is immutable and thread-safe, and its tables are large. */
    private static final TokenCounter CL100K =
            new TokenCounter(Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE));

    private final Encoding encoding;

    private TokenCounter(Encoding encoding) {
        this.encoding = encoding;
    }

    public static TokenCounter cl100k() {
        return CL100K;
    }

    public int count(String text) {
        return encoding.countTokensOrdinary(text);
    }

    /**
     * The longest prefix of {@code text} that is at most {@code maxTokens} tokens. Cuts at a token boundary; if that
     * boundary falls inside a multi-byte character, tokens are dropped from the end until the decoded text is a prefix.
     */
    public String truncate(String text, int maxTokens) {
        IntArrayList tokens = encoding.encodeOrdinary(text);
        if (tokens.size() <= maxTokens) {
            return text;
        }
        for (int n = maxTokens; n > 0; n--) {
            IntArrayList head = new IntArrayList(n);
            for (int i = 0; i < n; i++) {
                head.add(tokens.get(i));
            }
            String decoded = encoding.decode(head);
            if (text.startsWith(decoded)) {
                return decoded;
            }
        }
        return "";
    }
}
