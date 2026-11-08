package rag.app.support;

import java.util.List;
import java.util.Locale;
import rag.app.embed.Embedder;

/** Deterministic bag-of-words embedder: texts sharing words are close. No network. */
public class FakeEmbedder implements Embedder {

    private final int dims;

    public FakeEmbedder(int dims) {
        this.dims = dims;
    }

    @Override public String modelId() { return "fake/hash"; }
    @Override public String shortName() { return "fake"; }
    @Override public int dims() { return dims; }

    @Override
    public float[] embedQuery(String q) {
        return vector(q);
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        return texts.stream().map(this::vector).toList();
    }

    public float[] vector(String text) {
        float[] v = new float[dims];
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!w.isEmpty()) {
                v[Math.floorMod(w.hashCode(), dims)] += 1;
            }
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        if (norm == 0) {
            v[0] = 1;
            return v;
        }
        for (int i = 0; i < dims; i++) {
            v[i] /= (float) Math.sqrt(norm);
        }
        return v;
    }
}
