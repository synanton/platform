package org.synanton.synquest.ontology;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Loads ontology-v2.jsonl once and fails fast on contract violations.
 *
 * <p>The mapper is immutable after construction and safe for concurrent reads.
 */
public final class OntologyMapper {

    public static final int EXPECTED_ROWS = 273;
    public static final int EXPECTED_HARD = 198;
    public static final int EXPECTED_SOFT_GLOSSARY = 72;
    public static final int EXPECTED_SOFT_DOCTRINAL = 3;
    public static final int EXPECTED_GRAPH_USEFUL = 31;
    public static final int EXPECTED_PAIR_TOTAL = 38;
    public static final int EXPECTED_PAIR_HARD = 30;
    public static final int EXPECTED_PAIR_SOFT_GLOSSARY = 8;
    public static final int EXPECTED_PAIR_SOFT_DOCTRINAL = 0;

    private final List<OntologyEntity> entities;

    private OntologyMapper(List<OntologyEntity> entities) {
        this.entities = List.copyOf(entities);
    }

    public static OntologyMapper loadFrom(Path jsonlPath, String expectedSha256) throws IOException {
        if (expectedSha256 == null || expectedSha256.contains("…") || expectedSha256.length() != 64) {
            throw new IllegalStateException(
                    "ontology.v2.sha256 must be the full 64-char hex SHA; got: " + expectedSha256);
        }
        byte[] bytes = Files.readAllBytes(jsonlPath);
        String actualSha = sha256Hex(bytes);
        if (!actualSha.equalsIgnoreCase(expectedSha256)) {
            throw new IllegalStateException(
                    "Ontology SHA-256 mismatch: expected " + expectedSha256 + ", got " + actualSha);
        }

        List<OntologyEntity> rows = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        try (var lines = Files.lines(jsonlPath)) {
            var iterator = lines.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next().trim();
                if (line.isEmpty()) {
                    continue;
                }
                JsonNode node = mapper.readTree(line);
                rows.add(OntologyEntity.fromJson(node));
            }
        }

        OntologyMapper result = new OntologyMapper(rows);
        result.assertInvariants();
        return result;
    }

    public int size() {
        return entities.size();
    }

    public Optional<OntologyEntity> findById(String id) {
        return entities.stream().filter(entity -> entity.entityId().equals(id)).findFirst();
    }

    /**
     * Filters by entity type/class, df band, and class. {@code null} or
     * {@code all} means no constraint. Results are sorted by df desc, then
     * canonical form.
     */
    public List<OntologyEntity> filter(
            @Nullable String type, @Nullable String dfBand, @Nullable String entityClass) {
        List<OntologyEntity> result = new ArrayList<>();
        for (OntologyEntity entity : entities) {
            if (!matchesType(entity, type)) {
                continue;
            }
            if (!matchesClass(entity, entityClass)) {
                continue;
            }
            if (!matchesBand(entity, dfBand)) {
                continue;
            }
            result.add(entity);
        }
        result.sort((left, right) -> {
            int byDf = Integer.compare(right.df(), left.df());
            return byDf != 0 ? byDf : left.canonicalForm().compareTo(right.canonicalForm());
        });
        return result;
    }

    private void assertInvariants() {
        assertEqual("row count", EXPECTED_ROWS, entities.size());

        long hard = countClass(OntologyEntity.CLASS_HARD);
        long softGlossary = countClass(OntologyEntity.CLASS_SOFT_GLOSSARY);
        long softDoctrinal = countClass(OntologyEntity.CLASS_SOFT_DOCTRINAL);
        assertEqual("hard", EXPECTED_HARD, hard);
        assertEqual("soft_glossary", EXPECTED_SOFT_GLOSSARY, softGlossary);
        assertEqual("soft_doctrinal", EXPECTED_SOFT_DOCTRINAL, softDoctrinal);
        assertEqual("class total", EXPECTED_ROWS, hard + softGlossary + softDoctrinal);

        long graphUseful = entities.stream().filter(OntologyEntity::isGraphUseful).count();
        assertEqual("graph_useful", EXPECTED_GRAPH_USEFUL, graphUseful);

        List<OntologyEntity> pair = entities.stream()
                .filter(entity -> "pair".equals(entity.dfBand()))
                .toList();
        assertEqual("pair total", EXPECTED_PAIR_TOTAL, pair.size());
        assertEqual("pair hard", EXPECTED_PAIR_HARD,
                pair.stream().filter(entity -> OntologyEntity.CLASS_HARD.equals(entity.entityClass())).count());
        assertEqual("pair soft_glossary", EXPECTED_PAIR_SOFT_GLOSSARY,
                pair.stream().filter(entity -> OntologyEntity.CLASS_SOFT_GLOSSARY.equals(entity.entityClass())).count());
        assertEqual("pair soft_doctrinal", EXPECTED_PAIR_SOFT_DOCTRINAL,
                pair.stream().filter(entity -> OntologyEntity.CLASS_SOFT_DOCTRINAL.equals(entity.entityClass())).count());
    }

    private long countClass(String entityClass) {
        return entities.stream().filter(entity -> entityClass.equals(entity.entityClass())).count();
    }

    private static void assertEqual(String label, long expected, long actual) {
        if (expected != actual) {
            throw new IllegalStateException(
                    "Ontology invariant violated [" + label + "]: expected " + expected + ", got " + actual);
        }
    }

    private static boolean matchesType(OntologyEntity entity, @Nullable String type) {
        if (type == null || "all".equals(type)) {
            return true;
        }
        if (OntologyEntity.CLASS_HARD.equals(type)
                || OntologyEntity.CLASS_SOFT.equals(type)
                || OntologyEntity.CLASS_SOFT_GLOSSARY.equals(type)
                || OntologyEntity.CLASS_SOFT_DOCTRINAL.equals(type)) {
            return matchesClass(entity, type);
        }
        if ("glossary_term".equals(type)) {
            return "glossary".equals(entity.type()) || "filenet".equals(entity.type());
        }
        if ("doctrinal_term".equals(type)) {
            return "legal_doctrine".equals(entity.type());
        }
        return type.equals(entity.type());
    }

    private static boolean matchesClass(OntologyEntity entity, @Nullable String entityClass) {
        if (entityClass == null || "all".equals(entityClass)) {
            return true;
        }
        if (OntologyEntity.CLASS_SOFT.equals(entityClass)) {
            return entity.entityClass().startsWith("soft_");
        }
        return entityClass.equals(entity.entityClass());
    }

    private static boolean matchesBand(OntologyEntity entity, @Nullable String band) {
        if (band == null || "all".equals(band)) {
            return true;
        }
        return switch (band) {
            case "graph_useful" -> entity.isGraphUseful();
            case "df_1" -> entity.df() <= 1;
            case "pair" -> entity.df() == 2;
            case "df_3_15" -> entity.df() >= 3 && entity.df() <= 15;
            case "df_16_50" -> entity.df() >= 16 && entity.df() <= 50;
            case "df_over_50" -> entity.df() > 50;
            default -> false;
        };
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
