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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Authorized regex-only baseline smoke; no LLM, GPU, fallback, graph writes, or evaluation. */
public final class RegexBaselineSmoke {
    private static final String RUN_ID = "regex-baseline-smoke-v1";
    private static final String EVIDENCE_CONTRACT = "p3a-evidence-binding-v1";
    private static final String A2_VERSION = "it-books-a2-v1";

    private RegexBaselineSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path root = repositoryRoot();
        Path output = Path.of(argument(args, "--out",
            root.resolve("build/regex-baseline-smoke").toString()));
        Path a2 = root.resolve("evaluation/it-books/a2-v1");
        ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        JsonNode a2Manifest = mapper.readTree(a2.resolve("manifest.json").toFile());
        JsonNode a2Annotations = mapper.readTree(a2.resolve("annotations.json").toFile());
        JsonNode p0Report = mapper.readTree(root.resolve(
            "docs/implementation/it-books/p0/it-books.report.json").toFile());

        Set<String> extractionTypes = new java.util.HashSet<>();
        p0Report.path("coverage").path("classes").forEach(c -> {
            if ("EXTRACTION".equals(c.path("role").asText())) {
                extractionTypes.add(c.path("id").asText());
            }
        });
        Set<String> extractionPredicates = new java.util.HashSet<>();
        p0Report.path("coverage").path("relations").forEach(r -> extractionPredicates.add(r.path("id").asText()));

        String templateDigest = a2Manifest.path("compiler").path("templateDigest").asText();
        String compilationId = a2Manifest.path("compiler").path("compilationId").asText();
        EvidenceBinder binder = new EvidenceBinder();
        RegexExtractorAdapter adapter = new RegexExtractorAdapter();
        long started = System.nanoTime();

        ArrayNode sources = mapper.createArrayNode();
        Map<String, Integer> entityTypes = new TreeMap<>();
        Map<String, Integer> entityStatuses = new TreeMap<>();
        Map<String, Integer> relationStatuses = new TreeMap<>();
        Map<String, Integer> relationPredicates = new TreeMap<>();
        Map<String, Integer> unsupportedTypes = new TreeMap<>();
        Map<String, Integer> unsupportedPredicates = new TreeMap<>();
        int entityTotal = 0;
        int relationTotal = 0;
        int boundTotal = 0;
        long inputBytes = 0;

        for (JsonNode source : a2Manifest.path("sources")) {
            String sourceId = source.path("sourceId").asText();
            Path excerpt = root.resolve(source.path("excerptPath").asText());
            byte[] bytes = Files.readAllBytes(excerpt);
            inputBytes += bytes.length;
            DocumentStructure structure = DocumentStructureService.defaults().process(new SourceDocument(
                "it-books-smoke", sourceId, A2_VERSION, "a2-" + sourceId, "text/markdown", bytes));
            String structureDigest = StructuralArtifactDigest.sha256(structure);
            String textHash = sha256(structure.text());
            EvidenceBinder.Mapping mapping = new EvidenceBinder.Mapping(
                textHash, structureDigest, structure.chunkingConfig(), templateDigest,
                compilationId, List.of());

            ObjectNode sourceJson = mapper.createObjectNode();
            sourceJson.put("sourceId", sourceId);
            sourceJson.put("documentId", structure.documentId());
            sourceJson.put("excerptSha256", sha256(bytes));
            sourceJson.put("structuralArtifactDigest", structureDigest);
            sourceJson.put("normalizationVersion", structure.normalizationVersion());
            sourceJson.put("chunkingConfig", structure.chunkingConfig());
            ArrayNode chunksJson = sourceJson.putArray("chunks");

            for (DocumentStructure.Chunk chunk : structure.chunks()) {
                ObjectNode chunkJson = mapper.createObjectNode();
                chunkJson.put("chunkId", chunk.chunkId());
                chunkJson.put("chunkStartOffset", chunk.documentStartOffset());
                chunkJson.put("chunkEndOffset", chunk.documentEndOffset());
                ArrayNode entitiesJson = chunkJson.putArray("entities");
                ArrayNode relationsJson = chunkJson.putArray("relations");

                ExtractionResult result = adapter.extract(
                    new TextSegment(chunk.chunkId(), chunk.text(), 0),
                    new ExtractionContext("it-books-smoke", "a2-" + sourceId,
                        Set.of(), A2_VERSION));

                for (PreEntity entity : result.preEntities()) {
                    entityTotal++;
                    entityTypes.merge(entity.proposedType(), 1, Integer::sum);
                    if (!extractionTypes.contains(entity.proposedType())) {
                        unsupportedTypes.merge(entity.proposedType(), 1, Integer::sum);
                    }
                    EvidenceBinder.Result bound = binder.bind(structure, structure.text(), mapping,
                        new EvidenceBinder.Candidate(
                            "entity:" + sourceId + ":" + chunk.chunkId() + ":" + entity.mentionId(),
                            EvidenceBinder.Space.STRUCTURAL_CHUNK, entity.charStart(), entity.charEnd(),
                            chunk.chunkId(), EvidenceBinder.Grounding.EXACT, templateDigest, compilationId));
                    entityStatuses.merge(bound.status().name(), 1, Integer::sum);
                    if (bound.status() == EvidenceBinder.Status.RESOLVED) {
                        boundTotal++;
                    }
                    entitiesJson.add(bindingJson(mapper, entity.surfaceForm(), entity.proposedType(),
                        null, bound));
                }

                int relationOrdinal = 0;
                for (RelationCandidate relation : result.relations()) {
                    relationTotal++;
                    relationPredicates.merge(relation.predicate(), 1, Integer::sum);
                    if (!extractionPredicates.contains(relation.predicate())) {
                        unsupportedPredicates.merge(relation.predicate(), 1, Integer::sum);
                    }
                    EvidenceBinder.Result bound = binder.bind(structure, structure.text(), mapping,
                        new EvidenceBinder.Candidate(
                            "relation:" + sourceId + ":" + chunk.chunkId() + ":"
                                + relationOrdinal++,
                            EvidenceBinder.Space.STRUCTURAL_CHUNK,
                            relation.evidenceCharStart(), relation.evidenceCharEnd(),
                            relation.evidenceChunkId(), EvidenceBinder.Grounding.EXACT,
                            templateDigest, compilationId));
                    relationStatuses.merge(bound.status().name(), 1, Integer::sum);
                    if (bound.status() == EvidenceBinder.Status.RESOLVED) {
                        boundTotal++;
                    }
                    relationsJson.add(bindingJson(mapper, null, null,
                        relation.predicate(), bound));
                }
                chunksJson.add(chunkJson);
            }
            sources.add(sourceJson);
        }

        int accepted = 0;
        int rejected = 0;
        int unjudged = 0;
        for (JsonNode relation : a2Annotations.path("relations")) {
            switch (relation.path("judgment").asText()) {
                case "ACCEPTED" -> accepted++;
                case "REJECTED" -> rejected++;
                case "UNJUDGED" -> unjudged++;
                default -> throw new IllegalStateException("unknown A2 judgment");
            }
        }

        ObjectNode results = mapper.createObjectNode();
        results.put("runId", RUN_ID);
        results.put("adapter", "RegexExtractorAdapter");
        results.put("provider", "regex");
        results.put("configuration", "EXTRACTION_PROVIDER=regex");
        results.put("evidenceContract", EVIDENCE_CONTRACT);
        results.put("a2Version", A2_VERSION);
        results.set("sources", sources);
        ObjectNode counts = results.putObject("counts");
        counts.put("entitiesEmitted", entityTotal);
        counts.put("relationsEmitted", relationTotal);
        counts.put("evidenceSpansResolved", boundTotal);
        counts.set("entityBinding", mapper.valueToTree(entityStatuses));
        counts.set("relationBinding", mapper.valueToTree(relationStatuses));
        counts.set("entityTypes", mapper.valueToTree(entityTypes));
        counts.set("relationPredicates", mapper.valueToTree(relationPredicates));
        counts.set("unsupportedEntityTypes", mapper.valueToTree(unsupportedTypes));
        counts.set("unsupportedPredicates", mapper.valueToTree(unsupportedPredicates));
        ObjectNode a2Context = results.putObject("a2Context");
        a2Context.put("judgmentEvaluation", "NOT_EVALUATED");
        a2Context.put("accepted", accepted);
        a2Context.put("rejected", rejected);
        a2Context.put("unjudged", unjudged);
        a2Context.put("note", "A2 judgments are independent; regex output is not scored as precision/recall and missing output is not a negative judgment.");
        ObjectNode prohibitions = results.putObject("prohibitions");
        prohibitions.put("llm", true);
        prohibitions.put("gpu", true);
        prohibitions.put("fallback", true);
        prohibitions.put("graphWrites", true);
        prohibitions.put("fullEvaluation", true);

        Files.createDirectories(output);
        byte[] resultsBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(results);
        Files.write(output.resolve("results.json"), resultsBytes);

        long runtimeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        ObjectNode run = mapper.createObjectNode();
        run.put("runId", RUN_ID);
        run.put("startedAt", Instant.now().toString());
        run.put("gitCommit", git(root, "rev-parse", "HEAD"));
        run.put("resultsSha256", sha256(resultsBytes));
        run.put("runtimeMillis", runtimeMs);
        run.put("inputBytes", inputBytes);
        run.put("jvmMaxMemoryBytes", Runtime.getRuntime().maxMemory());
        run.put("a2Version", A2_VERSION);
        run.put("a2ManifestSha256", sha256(Files.readAllBytes(a2.resolve("manifest.json"))));
        run.put("a2AnnotationsSha256", sha256(Files.readAllBytes(a2.resolve("annotations.json"))));
        run.put("a2MergeCommit", "c790a2390404593f2807eaa53fa1ace04348bc17");
        run.put("p0MergeCommit", "5a781ef8e33ad294421fe3c2155b0afc01a9730d");
        run.put("p0ReleaseId", a2Manifest.path("compiler").path("releaseId").asText());
        run.put("p0ReleaseDigest", a2Manifest.path("compiler").path("releaseDigest").asText());
        run.put("p0SpecDigest", p0Report.path("digests").path("specDigest").asText());
        run.put("p0TemplateDigest", templateDigest);
        run.put("p0CompilationId", compilationId);
        run.put("p1StructuralSliceMerge", "1b1a90e7");
        run.put("p1ExtractionIntegrationMerge", "15c88641");
        run.put("p1ConformanceMerge", "5fa1df9d");
        run.put("p1PinnedEvidenceMerge", "99df0da7");
        run.put("p3aMergeCommit", "66afc878d467f95cc997eeb0e9201aa263f9cc1c");
        run.put("evidenceContract", EVIDENCE_CONTRACT);
        run.put("provider", "regex");
        run.put("configuration", "EXTRACTION_PROVIDER=regex");
        run.put("input", "it-books-a2-v1 excerpts; P1 STRUCTURAL_CHUNK");
        run.put("authorizationScope", "regex-baseline-smoke-only");
        run.put("fullEvaluationAuthorized", false);
        run.put("fullEvaluationStarted", false);
        run.put("llmUsed", false);
        run.put("gpuUsed", false);
        run.put("fallbackUsed", false);
        run.put("graphWrites", false);
        run.put("entitiesEmitted", entityTotal);
        run.put("relationsEmitted", relationTotal);
        run.put("evidenceSpansResolved", boundTotal);
        run.set("entityBinding", mapper.valueToTree(entityStatuses));
        run.set("relationBinding", mapper.valueToTree(relationStatuses));
        run.set("unsupportedEntityTypes", mapper.valueToTree(unsupportedTypes));
        run.set("unsupportedPredicates", mapper.valueToTree(unsupportedPredicates));
        Files.write(output.resolve("run-manifest.json"),
            mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(run));

        System.out.println("regex smoke wrote " + output);
        System.out.println("resultsSha256=" + sha256(resultsBytes));
        System.out.println("entities=" + entityTotal + " relations=" + relationTotal
            + " evidenceSpansResolved=" + boundTotal);
    }

    private static ObjectNode bindingJson(ObjectMapper mapper, String surface, String type,
                                          String predicate, EvidenceBinder.Result result) {
        ObjectNode node = mapper.createObjectNode();
        if (surface != null) {
            node.put("surface", surface);
        }
        if (type != null) {
            node.put("proposedType", type);
        }
        if (predicate != null) {
            node.put("predicate", predicate);
        }
        node.put("status", result.status().name());
        node.put("reason", result.reason());
        if (result.evidence() != null) {
            var evidence = result.evidence();
            node.put("space", evidence.inputSpace().name());
            node.put("grounding", evidence.grounding().name());
            node.put("chunkId", evidence.chunkId());
            node.put("documentStartOffset", evidence.documentStartOffset());
            node.put("documentEndOffset", evidence.documentEndOffset());
            node.set("elementIds", mapper.valueToTree(evidence.elementIds()));
            node.put("geometryStatus", evidence.geometry().status().name());
            node.put("text", evidence.text());
        }
        return node;
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

    private static String git(Path root, String... args) {
        try {
            List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.waitFor() != 0) {
                throw new IllegalStateException("git command failed: " + output);
            }
            return output;
        } catch (Exception e) {
            throw new IllegalStateException("git command failed", e);
        }
    }

    private static String argument(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length; i++) {
            if (name.equals(args[i]) && i + 1 < args.length) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
