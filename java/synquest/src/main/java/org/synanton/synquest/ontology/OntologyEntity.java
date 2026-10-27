package org.synanton.synquest.ontology;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One row of ontology-v2.jsonl.
 *
 * <p>The committed file stores the raw class as {@code hard|soft} plus a
 * {@code soft_subtype}. The public/API class is normalized to
 * {@code hard|soft_glossary|soft_doctrinal}.
 */
public record OntologyEntity(
        @JsonProperty("entity_id") String entityId,
        @JsonProperty("canonical_form") String canonicalForm,
        @JsonProperty("entity_class") String entityClass,
        String type,
        @JsonProperty("soft_subtype") @Nullable String softSubtype,
        @JsonProperty("entity_kind") String entityKind,
        int df,
        @JsonProperty("df_band") String dfBand,
        @JsonProperty("chunk_ids") List<String> chunkIds,
        List<String> aliases) {

    public static final String CLASS_HARD = "hard";
    public static final String CLASS_SOFT = "soft";
    public static final String CLASS_SOFT_GLOSSARY = "soft_glossary";
    public static final String CLASS_SOFT_DOCTRINAL = "soft_doctrinal";

    public static String computeBand(int df) {
        if (df <= 1) {
            return "1";
        }
        if (df == 2) {
            return "pair";
        }
        if (df <= 15) {
            return "3-15";
        }
        if (df <= 50) {
            return "16-50";
        }
        return ">50";
    }

    public boolean isGraphUseful() {
        return CLASS_HARD.equals(entityClass) && df >= 3 && df <= 15;
    }

    public static OntologyEntity fromJson(JsonNode node) {
        int df = node.path("df").asInt();
        String rawClass = node.path("entity_class").asText();
        String subtype = node.path("soft_subtype").asText(null);
        String effectiveClass = rawClass;
        if (CLASS_SOFT.equals(rawClass)) {
            if (!CLASS_SOFT_GLOSSARY.equals(subtype) && !CLASS_SOFT_DOCTRINAL.equals(subtype)) {
                throw new IllegalStateException(
                        "Ontology row " + node.path("entity_id").asText()
                                + " has invalid soft_subtype: " + subtype);
            }
            effectiveClass = subtype;
        } else if (!CLASS_HARD.equals(rawClass)) {
            throw new IllegalStateException(
                    "Ontology row " + node.path("entity_id").asText()
                            + " has unknown entity_class: " + rawClass);
        }
        return new OntologyEntity(
                node.path("entity_id").asText(),
                node.path("canonical_form").asText(),
                effectiveClass,
                node.path("type").asText(),
                subtype,
                node.path("entity_kind").asText(),
                df,
                computeBand(df),
                toStringList(node.path("chunk_ids")),
                List.of());
    }

    private static List<String> toStringList(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            values.add(value.asText());
        }
        return List.copyOf(values);
    }
}
