package org.synanton.relix.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class Pass2AnalysisParser {

    private static final Logger log = LoggerFactory.getLogger(Pass2AnalysisParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record TypedEntity(String label, String type, double confidence, List<Integer> chunkOrdinals) {}
    public record TypedRelation(String from, String to, String verb, double confidence, List<Integer> chunkOrdinals) {}
    public record Pass2Result(List<TypedEntity> entities, List<TypedRelation> relations) {}

    public Pass2Result parse(UUID contentRefId, int chunkOrdinal, String analysisJson) {
        try {
            JsonNode root = MAPPER.readTree(analysisJson);
            List<TypedEntity> entities = parseEntities(root);
            List<TypedRelation> relations = parseRelations(root);
            return new Pass2Result(entities, relations);
        } catch (Exception e) {
            log.warn("Failed to parse Pass-2 JSON for {}#{}: {}", contentRefId, chunkOrdinal, e.getMessage());
            return new Pass2Result(List.of(), List.of());
        }
    }

    private List<TypedEntity> parseEntities(JsonNode root) {
        List<TypedEntity> result = new ArrayList<>();
        JsonNode arr = root.path("typed_entities");
        if (arr.isMissingNode()) arr = root.path("entities");
        if (!arr.isArray()) return result;
        for (JsonNode n : arr) {
            String label = n.path("label").asText(null);
            String type = n.path("type").asText("Unknown");
            double conf = n.path("confidence").asDouble(1.0);
            List<Integer> ordinals = parseIntList(n.path("chunk_ordinals"));
            if (label != null && !label.isBlank()) {
                result.add(new TypedEntity(label, type, conf, ordinals));
            }
        }
        return result;
    }

    private List<TypedRelation> parseRelations(JsonNode root) {
        List<TypedRelation> result = new ArrayList<>();
        JsonNode arr = root.path("relations");
        if (!arr.isArray()) return result;
        for (JsonNode n : arr) {
            String from = n.path("from").asText(null);
            String to = n.path("to").asText(null);
            String verb = n.path("verb").asText(null);
            double conf = n.path("confidence").asDouble(1.0);
            List<Integer> ordinals = parseIntList(n.path("chunk_ordinals"));
            if (from != null && to != null && verb != null) {
                result.add(new TypedRelation(from, to, verb, conf, ordinals));
            }
        }
        return result;
    }

    private List<Integer> parseIntList(JsonNode node) {
        List<Integer> result = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) result.add(n.asInt());
        }
        return result;
    }
}
