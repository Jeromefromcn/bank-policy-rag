package rag.core.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import rag.core.model.Clearance;
import rag.core.model.ParsedDocument;
import rag.core.model.SectionMarker;
import rag.core.model.SectionPath;
import rag.core.model.SourceType;

/**
 * Parses an Example Bank or adversarial Markdown document (spec 01 §3.2–3.3). Front matter is
 * required; numbered headings ({@code ## 2 Title}) are the section markers.
 */
public final class MarkdownParser {

    private static final Pattern FRONT_MATTER = Pattern.compile("\\A---\\n(.*?)\\n---\\n", Pattern.DOTALL);
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}[ \\t]+(\\d+(?:\\.\\d+)*)[ \\t]+\\S");
    private static final List<String> REQUIRED = List.of("docId", "title", "version", "classification", "sourceType");

    private MarkdownParser() {}

    public static ParsedDocument parse(String content, String sourceName) {
        String normalised = content.replace("\r\n", "\n");
        Matcher fm = FRONT_MATTER.matcher(normalised);
        if (!fm.find()) {
            throw new IllegalArgumentException(sourceName + ": missing front matter");
        }
        Map<String, Object> meta = load(fm.group(1), sourceName);
        for (String key : REQUIRED) {
            Object value = meta.get(key);
            if (value == null || value.toString().isBlank()) {
                throw new IllegalArgumentException(sourceName + ": front matter lacks " + key);
            }
        }

        Clearance classification;
        SourceType sourceType;
        try {
            classification = Clearance.parse(meta.get("classification").toString());
            sourceType = SourceType.parse(meta.get("sourceType").toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(sourceName + ": " + e.getMessage(), e);
        }

        String text = normalised.substring(fm.end());
        List<SectionMarker> markers = new ArrayList<>();
        Matcher h = HEADING.matcher(text);
        while (h.find()) {
            markers.add(new SectionMarker(h.start(), SectionPath.parse(h.group(1))));
        }
        return new ParsedDocument(meta.get("docId").toString(), meta.get("title").toString(),
                meta.get("version").toString(), sourceType, classification, text, List.of(), markers);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String yaml, String sourceName) {
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(sourceName + ": front matter is not a mapping");
        }
        return (Map<String, Object>) map;
    }
}
