package rag.core.ids;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import rag.core.model.SectionPath;

/** Deterministic identifiers (ROADMAP §4.1–4.2). */
public final class Hashes {

    private Hashes() {}

    public static String sha256Hex(String s) {
        return sha256Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** First 16 hex of {@code sha256(docId + "#" + dotted section number)}. */
    public static String sectionId(String docId, SectionPath path) {
        return sha256Hex(docId + "#" + path.leaf()).substring(0, 16);
    }

    /** First 16 hex of {@code sha256(sectionId + ordinal + chunkerVersion)}. */
    public static String chunkId(String sectionId, int ordinal, String chunkerVersion) {
        return sha256Hex(sectionId + ordinal + chunkerVersion).substring(0, 16);
    }
}
