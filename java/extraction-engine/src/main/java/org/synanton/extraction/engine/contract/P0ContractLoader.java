package org.synanton.extraction.engine.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Loads the immutable P0 SPEC/template artifacts into a prompt-safe contract. */
public final class P0ContractLoader {
    private final ObjectMapper mapper = new ObjectMapper()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public CompiledExtractionContract load(Path specPath, Path templatePath, Path reportPath)
            throws IOException {
        JsonNode spec = mapper.readTree(Files.readString(specPath, StandardCharsets.UTF_8));
        JsonNode template = mapper.readTree(Files.readString(templatePath, StandardCharsets.UTF_8));
        JsonNode report = mapper.readTree(Files.readString(reportPath, StandardCharsets.UTF_8));
        JsonNode provenance = spec.path("provenance");
        String contractDigest = sha256(canonical(spec) + canonical(template));
        if (!provenance.path("releaseId").asText().equals(
            template.path("provenance").path("releaseId").asText())) {
            throw new IllegalArgumentException("SPEC/template release identity mismatch");
        }
        List<CompiledExtractionContract.EntityType> entities = new ArrayList<>();
        for (JsonNode entity : spec.path("entities")) {
            entities.add(new CompiledExtractionContract.EntityType(
                entity.path("id").asText(), entity.path("label").asText()));
        }
        List<CompiledExtractionContract.RelationType> relations = new ArrayList<>();
        for (JsonNode relation : spec.path("relations")) {
            relations.add(new CompiledExtractionContract.RelationType(
                relation.path("id").asText(), relation.path("label").asText(),
                strings(relation.path("subjects")), strings(relation.path("objects")),
                relation.path("status").asText()));
        }
        return new CompiledExtractionContract(
            provenance.path("releaseId").asText(),
            provenance.path("releaseDigest").asText(),
            provenance.path("compilerVersion").asText(),
            provenance.path("exportProfile").asText(),
            provenance.path("schemaVersion").asText(),
            digest(report.path("digests").path("specDigest").asText()),
            digest(report.path("digests").path("templateDigest").asText()),
            digest(report.path("digests").path("compilationId").asText()),
            contractDigest, entities, relations);
    }

    private String canonical(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot canonicalize P0 artifact", e);
        }
    }

    private static String digest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("P0 artifact missing a SHA-256 digest");
        }
        return value;
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(node -> result.add(node.asText()));
        return result;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
