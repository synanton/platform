package org.synanton.synflux.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.PreEntity;
import org.synanton.extraction.engine.RelationCandidate;
import org.synanton.extraction.engine.TextSegment;
import org.synanton.extraction.engine.regex.RegexExtractorAdapter;
import org.synanton.synflux.document.DocumentStructure;
import org.synanton.synflux.document.DocumentStructureService;
import org.synanton.synflux.document.SourceDocument;
import org.synanton.synflux.document.StructuralArtifactDigest;
import org.synanton.synflux.document.evidence.EvidenceBinder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * A4 full regex baseline. This is an evaluation harness, not a production
 * extractor. It reports mention, resolution, relation/ontology, graph
 * persistence, and determinism separately; it never writes a cluster graph.
 */
public final class RegexBaselineA4 {
    private static final String RUN_ID = "regex-baseline-a4-v1";
    private static final String A2_VERSION = "it-books-a2-v1";
    private static final String EVIDENCE_CONTRACT = "p3a-evidence-binding-v1";

    private static final Map<String, RelationSpec> A5 = a5Matrix();

    private RegexBaselineA4() {}

    public static void main(String[] args) throws Exception {
        Path root = repositoryRoot();
        Path output = Path.of(argument(args, "--out",
            root.resolve("build/a4-regex-baseline").toString()));
        String inspectionArg = argument(args, "--inspection-out", null);
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        Path a2 = root.resolve("evaluation/it-books/a2-v1");
        JsonNode manifest = mapper.readTree(a2.resolve("manifest.json").toFile());
        JsonNode annotations = mapper.readTree(a2.resolve("annotations.json").toFile());
        JsonNode p0 = mapper.readTree(root.resolve(
            "docs/implementation/it-books/p0/it-books.report.json").toFile());

        long started = System.nanoTime();
        Evaluation first = build(mapper, root, manifest, annotations, p0);
        byte[] firstBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(first.results);
        Evaluation second = build(mapper, root, manifest, annotations, p0);
        byte[] secondBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(second.results);

        Files.createDirectories(output);
        Files.write(output.resolve("results.json"), firstBytes);
        if (inspectionArg != null) {
            Path inspection = Path.of(inspectionArg);
            Files.createDirectories(inspection);
            Files.write(inspection.resolve("candidates.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(first.inspection));
        }
        ObjectNode run = mapper.createObjectNode();
        run.put("runId", RUN_ID);
        run.put("startedAt", Instant.now().toString());
        run.put("gitCommit", git(root, "rev-parse", "HEAD"));
        run.put("a2Version", A2_VERSION);
        run.put("a2ManifestSha256", sha256(Files.readAllBytes(a2.resolve("manifest.json"))));
        run.put("a2AnnotationsSha256", sha256(Files.readAllBytes(a2.resolve("annotations.json"))));
        run.put("p0ReleaseId", manifest.path("compiler").path("releaseId").asText());
        run.put("p0ReleaseDigest", manifest.path("compiler").path("releaseDigest").asText());
        run.put("p0SpecDigest", p0.path("digests").path("specDigest").asText());
        run.put("p0TemplateDigest", manifest.path("compiler").path("templateDigest").asText());
        run.put("p0CompilationId", manifest.path("compiler").path("compilationId").asText());
        run.put("evidenceContract", EVIDENCE_CONTRACT);
        run.put("provider", "regex");
        run.put("configuration", "EXTRACTION_PROVIDER=regex");
        run.put("llmUsed", false);
        run.put("gpuUsed", false);
        run.put("fallbackUsed", false);
        run.put("productionGraphWrites", false);
        run.put("resultsSha256", sha256(firstBytes));
        run.put("repeatResultsSha256", sha256(secondBytes));
        run.put("resultsIdentical", java.util.Arrays.equals(firstBytes, secondBytes));
        run.put("runtimeMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        run.put("inputBytes", first.results.path("inputBytes").asLong());
        Files.write(output.resolve("run-manifest.json"),
            mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(run));
        System.out.println("wrote " + output);
        System.out.println("resultsSha256=" + sha256(firstBytes)
            + " identical=" + java.util.Arrays.equals(firstBytes, secondBytes));
        System.out.println("mentions=" + first.results.path("stages").path("mentionExtraction").path("predicted").asInt()
            + " relations=" + first.results.path("stages").path("relationCandidates").path("predicted").asInt());
    }

    private static Evaluation build(ObjectMapper mapper, Path root, JsonNode manifest,
                                    JsonNode annotations, JsonNode p0) throws Exception {
        Map<String, JsonNode> a2MentionById = new LinkedHashMap<>();
        Map<String, String> a2MentionBySpan = new LinkedHashMap<>();
        for (JsonNode mention : annotations.path("mentions")) {
            String id = mention.path("id").asText();
            a2MentionById.put(id, mention);
            a2MentionBySpan.put(spanKey(mention.path("sourceId").asText(),
                mention.path("documentStartOffset").asInt(), mention.path("documentEndOffset").asInt()), id);
        }
        Map<String, JsonNode> a2RelationByKey = new LinkedHashMap<>();
        Set<String> acceptedKeys = new LinkedHashSet<>();
        Set<String> rejectedKeys = new LinkedHashSet<>();
        Set<String> unjudgedKeys = new LinkedHashSet<>();
        for (JsonNode relation : annotations.path("relations")) {
            String key = relationKey(relation.path("sourceId").asText(),
                relation.path("subjectMentionId").asText(), relation.path("predicate").asText(),
                relation.path("objectMentionId").asText());
            a2RelationByKey.put(key, relation);
            switch (relation.path("judgment").asText()) {
                case "ACCEPTED" -> acceptedKeys.add(key);
                case "REJECTED" -> rejectedKeys.add(key);
                case "UNJUDGED" -> unjudgedKeys.add(key);
                default -> throw new IllegalStateException("unknown A2 judgment");
            }
        }

        List<PredictedMention> predictedMentions = new ArrayList<>();
        List<PredictedRelation> predictedRelations = new ArrayList<>();
        Map<String, Integer> evidenceStatuses = new TreeMap<>();
        Map<String, String> entityEvidenceStatuses = new LinkedHashMap<>();
        Map<String, String> relationEvidenceStatuses = new LinkedHashMap<>();
        long inputBytes = 0;
        String templateDigest = manifest.path("compiler").path("templateDigest").asText();
        String compilationId = manifest.path("compiler").path("compilationId").asText();
        EvidenceBinder binder = new EvidenceBinder();
        RegexExtractorAdapter adapter = new RegexExtractorAdapter();

        for (JsonNode source : manifest.path("sources")) {
            String sourceId = source.path("sourceId").asText();
            byte[] bytes = Files.readAllBytes(root.resolve(source.path("excerptPath").asText()));
            inputBytes += bytes.length;
            DocumentStructure structure = DocumentStructureService.defaults().process(new SourceDocument(
                "it-books-a4", sourceId, A2_VERSION, "a2-" + sourceId, "text/markdown", bytes));
            EvidenceBinder.Mapping mapping = new EvidenceBinder.Mapping(sha256(structure.text()),
                StructuralArtifactDigest.sha256(structure), structure.chunkingConfig(), templateDigest,
                compilationId, List.of());
            for (DocumentStructure.Chunk chunk : structure.chunks()) {
                ExtractionResult result = adapter.extract(new TextSegment(chunk.chunkId(), chunk.text(), 0),
                    new ExtractionContext("it-books-a4", "a2-" + sourceId, Set.of(), A2_VERSION));
                Map<String, PredictedMention> byMentionId = new HashMap<>();
                for (PreEntity entity : result.preEntities()) {
                    int start = chunk.documentStartOffset() + entity.charStart();
                    int end = chunk.documentStartOffset() + entity.charEnd();
                    PredictedMention mention = new PredictedMention(sourceId, chunk.chunkId(),
                        entity.mentionId(), entity.surfaceForm(), entity.proposedType(), start, end);
                    predictedMentions.add(mention);
                    byMentionId.put(mention.mentionId, mention);
                    EvidenceBinder.Result bound = binder.bind(structure, structure.text(), mapping,
                        new EvidenceBinder.Candidate("a4-entity:" + sourceId + ":" + chunk.chunkId()
                            + ":" + entity.mentionId(), EvidenceBinder.Space.STRUCTURAL_CHUNK,
                            entity.charStart(), entity.charEnd(), chunk.chunkId(),
                            EvidenceBinder.Grounding.EXACT, templateDigest, compilationId));
                    evidenceStatuses.merge("entity:" + bound.status().name(), 1, Integer::sum);
                    entityEvidenceStatuses.put(sourceId + "|" + entity.mentionId(), bound.status().name());
                }
                for (RelationCandidate relation : result.relations()) {
                    PredictedMention subject = byMentionId.get(relation.subjectMentionId());
                    PredictedMention object = byMentionId.get(relation.objectMentionId());
                    predictedRelations.add(new PredictedRelation(sourceId, chunk.chunkId(),
                        relation.predicate(), subject, object));
                    EvidenceBinder.Result bound = binder.bind(structure, structure.text(), mapping,
                        new EvidenceBinder.Candidate("a4-relation:" + sourceId + ":" + chunk.chunkId()
                            + ":" + predictedRelations.size(), EvidenceBinder.Space.STRUCTURAL_CHUNK,
                            relation.evidenceCharStart(), relation.evidenceCharEnd(),
                            relation.evidenceChunkId(), EvidenceBinder.Grounding.EXACT,
                            templateDigest, compilationId));
                    evidenceStatuses.merge("relation:" + bound.status().name(), 1, Integer::sum);
                    relationEvidenceStatuses.put(sourceId + "|" + chunk.chunkId() + "|"
                        + relation.predicate() + "|" + relation.subjectMentionId() + "|"
                        + relation.objectMentionId(), bound.status().name());
                }
            }
        }

        Map<String, String> predictedMentionToA2 = new LinkedHashMap<>();
        int matchedMentions = 0;
        int typedMatches = 0;
        for (PredictedMention mention : predictedMentions) {
            String id = a2MentionBySpan.get(spanKey(mention.sourceId, mention.start, mention.end));
            if (id != null) {
                matchedMentions++;
                predictedMentionToA2.put(mention.sourceId + "|" + mention.mentionId, id);
                JsonNode expected = a2MentionById.get(id);
                if (mention.type.equals(expected.path("type").asText())) {
                    typedMatches++;
                }
            }
        }
        int predictedMentionCount = predictedMentions.size();
        int a2MentionCount = annotations.path("mentions").size();
        int missingMentions = a2MentionCount - matchedMentions;
        double mentionPrecision = ratio(matchedMentions, predictedMentionCount);
        double mentionRecall = ratio(matchedMentions, a2MentionCount);
        double mentionF1 = f1(mentionPrecision, mentionRecall);
        double typeAccuracy = ratio(typedMatches, matchedMentions);

        Set<String> predictedKeys = new LinkedHashSet<>();
        int ontologyValid = 0;
        int domainRangeViolations = 0;
        int unknownPredicates = 0;
        int missingEndpoints = 0;
        for (PredictedRelation relation : predictedRelations) {
            if (relation.subject == null || relation.object == null) {
                missingEndpoints++;
                continue;
            }
            if (!A5.containsKey(relation.predicate)) {
                unknownPredicates++;
            } else {
                RelationSpec spec = A5.get(relation.predicate);
                if (!spec.subjects.contains(relation.subject.type)
                    || !spec.objects.contains(relation.object.type)) {
                    domainRangeViolations++;
                } else {
                    ontologyValid++;
                }
            }
            String subjectA2 = predictedMentionToA2.get(relation.sourceId + "|" + relation.subject.mentionId);
            String objectA2 = predictedMentionToA2.get(relation.sourceId + "|" + relation.object.mentionId);
            if (subjectA2 != null && objectA2 != null) {
                predictedKeys.add(relationKey(relation.sourceId, subjectA2,
                    relation.predicate, objectA2));
            }
        }
        int matchedAccepted = intersection(predictedKeys, acceptedKeys);
        int matchedRejected = intersection(predictedKeys, rejectedKeys);
        int matchedUnjudged = intersection(predictedKeys, unjudgedKeys);
        int relationPredicted = predictedRelations.size();
        int relationFalsePositives = relationPredicted - matchedAccepted;
        int relationFalseNegatives = acceptedKeys.size() - matchedAccepted;
        double relationPrecision = ratio(matchedAccepted, relationPredicted);
        double relationRecall = ratio(matchedAccepted, acceptedKeys.size());
        double relationF1 = f1(relationPrecision, relationRecall);

        ObjectNode results = mapper.createObjectNode();
        results.put("runId", RUN_ID);
        results.put("provider", "regex");
        results.put("configuration", "EXTRACTION_PROVIDER=regex");
        results.put("a2Version", A2_VERSION);
        results.put("inputBytes", inputBytes);
        results.put("evidenceContract", EVIDENCE_CONTRACT);
        results.set("evidenceBinding", mapper.valueToTree(evidenceStatuses));

        ObjectNode stages = results.putObject("stages");
        ObjectNode mentionStage = stages.putObject("mentionExtraction");
        mentionStage.put("predicted", predictedMentionCount);
        mentionStage.put("expected", a2MentionCount);
        mentionStage.put("matchedExactSpan", matchedMentions);
        mentionStage.put("missing", missingMentions);
        mentionStage.put("unmatchedPredicted", predictedMentionCount - matchedMentions);
        mentionStage.put("precision", mentionPrecision);
        mentionStage.put("recall", mentionRecall);
        mentionStage.put("f1", mentionF1);
        mentionStage.put("exactSpanAndType", typedMatches);
        mentionStage.put("typeAccuracyAmongMatched", typeAccuracy);
        mentionStage.put("matchingRule", "one-to-one exact source + document UTF-16 span; type is scored separately");

        ObjectNode resolution = stages.putObject("canonicalResolution");
        resolution.put("available", false);
        resolution.put("mapped", 0);
        resolution.put("unresolved", predictedMentionCount);
        resolution.put("incorrect", 0);
        resolution.put("mentionToAnnotationMatches", matchedMentions);
        resolution.put("reason", "A8 deterministic canonical resolution is not implemented; A4 records the unresolved baseline only");

        ObjectNode relationStage = stages.putObject("relationCandidates");
        relationStage.put("predicted", relationPredicted);
        relationStage.put("acceptedA2", acceptedKeys.size());
        relationStage.put("matchedAccepted", matchedAccepted);
        relationStage.put("matchedRejected", matchedRejected);
        relationStage.put("matchedUnjudged", matchedUnjudged);
        relationStage.put("falsePositives", relationFalsePositives);
        relationStage.put("falseNegatives", relationFalseNegatives);
        relationStage.put("precision", relationPrecision);
        relationStage.put("recall", relationRecall);
        relationStage.put("f1", relationF1);
        relationStage.put("ontologyValid", ontologyValid);
        relationStage.put("unknownPredicates", unknownPredicates);
        relationStage.put("domainRangeViolations", domainRangeViolations);
        relationStage.put("missingEndpoints", missingEndpoints);
        relationStage.put("judgmentRule", "accepted matches require both A2 endpoint mentions and exact predicate; REJECTED and UNJUDGED remain distinct; missing predictions are not negative judgments");

        ObjectNode graph = stages.putObject("isolatedGraphPersistence");
        graph.put("regexAcceptedCandidates", ontologyValid);
        graph.put("regexPersistedEdges", 0);
        graph.put("regexRejectedCandidates", relationPredicted - ontologyValid);
        graph.put("productionGraphWrites", false);
        graph.put("note", "A5-valid, endpoint-resolved regex candidates are persisted by the isolated GraphWriter test; this run produced none");

        results.set("a2Context", mapper.valueToTree(Map.of(
            "accepted", acceptedKeys.size(),
            "rejected", rejectedKeys.size(),
            "unjudged", unjudgedKeys.size(),
            "judgmentEvaluation", relationPredicted > 0 ? "EVALUATED_SEPARATELY_BY_STAGE" : "NO_PREDICTIONS")));

        ObjectNode inspection = mapper.createObjectNode();
        inspection.put("runId", RUN_ID);
        inspection.put("a2Version", A2_VERSION);
        inspection.put("note", "Read-only A4.1 diagnostic export. Regex has no prompt or model response; those fields are explicitly unavailable.");
        inspection.put("promptAvailable", false);
        inspection.put("rawModelResponseAvailable", false);
        ArrayNode mentionCandidates = inspection.putArray("mentions");
        for (PredictedMention mention : predictedMentions) {
            ObjectNode node = mentionCandidates.addObject();
            node.put("sourceId", mention.sourceId);
            node.put("chunkId", mention.chunkId);
            node.put("mentionId", mention.mentionId);
            node.put("surface", mention.surface);
            node.put("type", mention.type);
            node.put("documentStartOffset", mention.start);
            node.put("documentEndOffset", mention.end);
            String a2Id = predictedMentionToA2.get(mention.sourceId + "|" + mention.mentionId);
            if (a2Id == null) {
                node.putNull("a2MentionId");
                node.putNull("a2Type");
                node.put("typeMatchesA2", false);
            } else {
                JsonNode expected = a2MentionById.get(a2Id);
                node.put("a2MentionId", a2Id);
                node.put("a2Type", expected.path("type").asText());
                node.put("typeMatchesA2", mention.type.equals(expected.path("type").asText()));
            }
            node.put("evidenceStatus", entityEvidenceStatuses.get(
                mention.sourceId + "|" + mention.mentionId));
        }
        ArrayNode relationCandidates = inspection.putArray("relations");
        for (PredictedRelation relation : predictedRelations) {
            ObjectNode node = relationCandidates.addObject();
            node.put("sourceId", relation.sourceId);
            node.put("chunkId", relation.chunkId);
            node.put("predicate", relation.predicate);
            node.put("subjectMentionId", relation.subject == null ? null : relation.subject.mentionId);
            node.put("subjectType", relation.subject == null ? null : relation.subject.type);
            node.put("subjectSurface", relation.subject == null ? null : relation.subject.surface);
            node.put("objectMentionId", relation.object == null ? null : relation.object.mentionId);
            node.put("objectType", relation.object == null ? null : relation.object.type);
            node.put("objectSurface", relation.object == null ? null : relation.object.surface);
            node.put("evidenceStatus", relationEvidenceStatuses.get(relation.sourceId + "|"
                + relation.chunkId + "|" + relation.predicate + "|"
                + (relation.subject == null ? null : relation.subject.mentionId) + "|"
                + (relation.object == null ? null : relation.object.mentionId)));
            String subjectA2 = relation.subject == null ? null
                : predictedMentionToA2.get(relation.sourceId + "|" + relation.subject.mentionId);
            String objectA2 = relation.object == null ? null
                : predictedMentionToA2.get(relation.sourceId + "|" + relation.object.mentionId);
            String relationKey = subjectA2 == null || objectA2 == null ? null
                : relationKey(relation.sourceId, subjectA2, relation.predicate, objectA2);
            JsonNode judged = relationKey == null ? null : a2RelationByKey.get(relationKey);
            if (judged == null) {
                node.put("a2RelationId", "");
                node.put("a2Judgment", "UNMATCHED");
            } else {
                node.put("a2RelationId", judged.path("id").asText());
                node.put("a2Judgment", judged.path("judgment").asText());
            }
            node.put("a5Outcome", validationOutcome(relation));
        }
        return new Evaluation(results, inspection);
    }

    private static Map<String, RelationSpec> a5Matrix() {
        Map<String, RelationSpec> m = new LinkedHashMap<>();
        m.put("AUTHORED_BY", new RelationSpec(Set.of("Book"), Set.of("Author")));
        m.put("HAS_CHAPTER", new RelationSpec(Set.of("Book"), Set.of("Chapter")));
        m.put("HAS_SECTION", new RelationSpec(Set.of("Chapter"), Set.of("Section")));
        m.put("COVERS_TOPIC", new RelationSpec(Set.of("Book", "Chapter", "Section"), Set.of("Topic")));
        m.put("DEFINES", new RelationSpec(Set.of("Chapter", "Section"), Set.of("Concept", "Definition")));
        m.put("USES", new RelationSpec(
            Set.of("Chapter", "Section", "Algorithm", "DataStructure", "Database", "DistributedSystem"),
            Set.of("Algorithm", "Concept", "DataStructure", "Database", "DistributedSystem")));
        m.put("DEPENDS_ON", new RelationSpec(
            Set.of("Algorithm", "Concept", "DataStructure", "Database", "DistributedSystem"),
            Set.of("Algorithm", "Concept", "DataStructure", "Database", "DistributedSystem")));
        m.put("PART_OF", new RelationSpec(
            Set.of("Chapter", "Section", "Topic"), Set.of("Book", "Chapter", "Section")));
        return Map.copyOf(m);
    }

    private static String validationOutcome(PredictedRelation relation) {
        if (relation.subject == null || relation.object == null) {
            return "MISSING_ENDPOINT";
        }
        RelationSpec spec = A5.get(relation.predicate);
        if (spec == null) {
            return "UNKNOWN_PREDICATE";
        }
        return spec.subjects.contains(relation.subject.type)
            && spec.objects.contains(relation.object.type) ? "VALID" : "DOMAIN_RANGE";
    }

    private static String spanKey(String source, int start, int end) {
        return source + "|" + start + "|" + end;
    }

    private static String relationKey(String source, String subject, String predicate, String object) {
        return source + "|" + subject + "|" + predicate + "|" + object;
    }

    private static int intersection(Set<String> left, Set<String> right) {
        int count = 0;
        for (String value : left) if (right.contains(value)) count++;
        return count;
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static double f1(double precision, double recall) {
        return precision + recall == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
    }

    private static String argument(String[] args, String name, String fallback) {
        for (int i = 0; i + 1 < args.length; i++) if (name.equals(args[i])) return args[i + 1];
        return fallback;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))
                && Files.exists(current.resolve("evaluation/it-books/a2-v1/manifest.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    private static String git(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(root.toFile())
            .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0) throw new IllegalStateException("git failed: " + output);
        return output;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Evaluation(ObjectNode results, ObjectNode inspection) {}
    private record RelationSpec(Set<String> subjects, Set<String> objects) {}
    private record PredictedMention(String sourceId, String chunkId, String mentionId,
                                    String surface, String type, int start, int end) {}
    private record PredictedRelation(String sourceId, String chunkId, String predicate,
                                     PredictedMention subject, PredictedMention object) {}
}
