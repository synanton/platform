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
import org.synanton.extraction.engine.llm.LlmExtractionAdapter;
import org.synanton.extraction.engine.contract.CompiledExtractionContract;
import org.synanton.extraction.engine.contract.P0ContractLoader;
import org.synanton.extraction.engine.capture.FileSystemCaptureStore;
import org.synanton.gpu.client.GpuPlaneClientProperties;
import org.synanton.gpu.client.GpuPlaneSynthesisClient;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Authorized gateway-compliant semantic evaluation. No direct vLLM/TEI and no graph writes. */
public final class GpuSemanticEvaluation {
    private static final String RUN_ID = "it-books-gateway-evaluation-v1";
    private static final String A2_VERSION = "it-books-a2-v1";
    private static final String PROMPT_VERSION = "extraction-v1";

    private GpuSemanticEvaluation() {
    }

    public static void main(String[] args) throws Exception {
        Path root = repositoryRoot();
        Path output = Path.of(arg(args, "--out", root.resolve("build/gateway-eval").toString()));
        String endpoint = arg(args, "--endpoint", "localhost:19090");
        String ca = arg(args, "--ca", root.resolve("../gpu-runtime/deployments/external/certs/ca.crt").normalize().toString());
        String cert = arg(args, "--cert", root.resolve("../gpu-runtime/deployments/external/certs/synanton-platform.crt").normalize().toString());
        String key = arg(args, "--key", root.resolve("../gpu-runtime/deployments/external/certs/synanton-platform.key").normalize().toString());
        String authority = arg(args, "--authority", "gpu-gateway");
        String model = arg(args, "--model", "synanton-qwen3-4b-synthesis");
        String tenant = arg(args, "--tenant", "it-books-eval");
        String gatewayImage = arg(args, "--gateway-image", "unknown");
        String captureDir = arg(args, "--capture", output.resolve("capture").toString());
        String modelRevision = arg(args, "--model-revision", "qwen3-4b-instruct-2507/vllm-openai:v0.29.0");

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        Path a2 = root.resolve("evaluation/it-books/a2-v1");
        JsonNode a2Manifest = mapper.readTree(a2.resolve("manifest.json").toFile());
        JsonNode annotations = mapper.readTree(a2.resolve("annotations.json").toFile());
        Path p0Dir = root.resolve("docs/implementation/it-books/p0");
        JsonNode p0 = mapper.readTree(p0Dir.resolve("it-books.report.json").toFile());
        CompiledExtractionContract contract = new P0ContractLoader().load(
            p0Dir.resolve("it-books.spec.json"), p0Dir.resolve("it-books.template.json"),
            p0Dir.resolve("it-books.report.json"));
        Set<String> types = new HashSet<>();
        p0.path("coverage").path("classes").forEach(c -> {
            if ("EXTRACTION".equals(c.path("role").asText())) types.add(c.path("id").asText());
        });
        Set<String> predicates = new HashSet<>();
        p0.path("coverage").path("relations").forEach(r -> predicates.add(r.path("id").asText()));

        GpuPlaneClientProperties props = new GpuPlaneClientProperties();
        props.setEndpoint(endpoint);
        props.setDefaultTenant(tenant);
        props.setTimeoutMs(240_000);
        props.setModelVersion("latest");
        props.getTls().setEnabled(true);
        props.getTls().setCaPath(ca);
        props.getTls().setCertPath(cert);
        props.getTls().setKeyPath(key);
        props.getTls().setAuthority(authority);

        Files.createDirectories(output);
        try (GpuPlaneSynthesisClient client = new GpuPlaneSynthesisClient(props, mapper)) {
            Files.createDirectories(Path.of(captureDir));
            LlmExtractionAdapter adapter = new LlmExtractionAdapter(
                client, model, 4096, new FileSystemCaptureStore(Path.of(captureDir)), PROMPT_VERSION);
            Run first = runOnce(mapper, root, a2Manifest, annotations, adapter, tenant, types, predicates);
            Run second = runOnce(mapper, root, a2Manifest, annotations, adapter, tenant, types, predicates);
            write(output.resolve("run1-results.json"), first.results, mapper);
            write(output.resolve("run2-results.json"), second.results, mapper);

            ObjectNode manifest = mapper.createObjectNode();
            manifest.put("runId", RUN_ID);
            manifest.put("startedAt", Instant.now().toString());
            manifest.put("gitCommit", git(root, "rev-parse", "HEAD"));
            manifest.put("a2Version", A2_VERSION);
            manifest.put("a2ManifestSha256", sha256(Files.readAllBytes(a2.resolve("manifest.json"))));
            manifest.put("a2AnnotationsSha256", sha256(Files.readAllBytes(a2.resolve("annotations.json"))));
            manifest.put("p0ReleaseId", a2Manifest.path("compiler").path("releaseId").asText());
            manifest.put("p0ReleaseDigest", a2Manifest.path("compiler").path("releaseDigest").asText());
            manifest.put("p0SpecDigest", p0.path("digests").path("specDigest").asText());
            manifest.put("p0TemplateDigest", a2Manifest.path("compiler").path("templateDigest").asText());
            manifest.put("p0CompilationId", a2Manifest.path("compiler").path("compilationId").asText());
            manifest.put("p1StructuralSliceMerge", "1b1a90e7");
            manifest.put("p1ExtractionIntegrationMerge", "15c88641");
            manifest.put("p1ConformanceMerge", "5fa1df9d");
            manifest.put("p1PinnedEvidenceMerge", "99df0da7");
            manifest.put("p3aContractVersion", "p3a-evidence-binding-v1");
            manifest.put("p3aMergeCommit", "66afc878d467f95cc997eeb0e9201aa263f9cc1c");
            manifest.put("gatewayContract", "synanton.gpu.v1/SYNTHESIZE");
            manifest.put("gatewayEndpoint", endpoint);
            manifest.put("gatewayImage", gatewayImage);
            manifest.put("provider", "LOCAL");
            manifest.put("model", model);
            manifest.put("modelRevision", modelRevision);
            manifest.put("promptVersion", PROMPT_VERSION);
            manifest.put("compiledContractDigest", contract.contractDigest());
            manifest.put("compiledContractReleaseId", contract.releaseId());
            manifest.put("compiledContractReleaseDigest", contract.releaseDigest());
            manifest.put("tenant", tenant);
            manifest.put("temperature", 0.0);
            manifest.put("maxTokens", 4096);
            manifest.put("timeoutMs", 240_000);
            manifest.put("retryPolicy", "gateway transient retry; no provider fallback");
            manifest.put("directVllmOrTeiCalls", false);
            manifest.put("automaticFallback", false);
            manifest.put("graphWrites", false);
            manifest.put("productionIndexes", false);
            manifest.set("run1", first.summary);
            manifest.set("run2", second.summary);
            manifest.put("metricDefinitions", "entity TP = same source/type and overlapping span, greedy one-to-one; relation TP = same predicate and matched endpoints against ACCEPTED; REJECTED/unknown predictions are FP; UNJUDGED predictions are excluded; unresolved spans are not counted as entity TP");
            manifest.put("run1ResultsSha256", sha256(Files.readAllBytes(output.resolve("run1-results.json"))));
            manifest.put("run2ResultsSha256", sha256(Files.readAllBytes(output.resolve("run2-results.json"))));
            manifest.put("resultsIdentical", first.results.equals(second.results));
            write(output.resolve("run-manifest.json"), manifest, mapper);
            System.out.println("wrote " + output);
            System.out.println("run1 " + first.summary.path("metrics"));
            System.out.println("run2 " + second.summary.path("metrics"));
            System.out.println("identical=" + manifest.path("resultsIdentical").asBoolean());
        }
    }

    private record Run(ObjectNode results, ObjectNode summary) {}

    private static Run runOnce(ObjectMapper mapper, Path root, JsonNode a2Manifest, JsonNode annotations,
                               LlmExtractionAdapter adapter, String tenant, Set<String> types,
                               Set<String> predicates) throws Exception {
        long started = System.nanoTime();
        String template = a2Manifest.path("compiler").path("templateDigest").asText();
        String compilation = a2Manifest.path("compiler").path("compilationId").asText();
        EvidenceBinder binder = new EvidenceBinder();
        ArrayNode predictedMentions = mapper.createArrayNode();
        ArrayNode predictedRelations = mapper.createArrayNode();
        ArrayNode sources = mapper.createArrayNode();
        Map<String, Integer> bindings = new TreeMap<>();
        Map<String, Integer> emittedTypes = new TreeMap<>();
        Map<String, Integer> emittedPredicates = new TreeMap<>();
        Map<String, Integer> unsupportedTypes = new TreeMap<>();
        Map<String, Integer> unsupportedPredicates = new TreeMap<>();
        int quarantined = 0;
        int failedChunks = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        long inputBytes = 0;

        for (JsonNode source : a2Manifest.path("sources")) {
            String sourceId = source.path("sourceId").asText();
            byte[] bytes = Files.readAllBytes(root.resolve(source.path("excerptPath").asText()));
            inputBytes += bytes.length;
            DocumentStructure structure = DocumentStructureService.defaults().process(new SourceDocument(
                tenant, sourceId, A2_VERSION, "a2-" + sourceId, "text/markdown", bytes));
            EvidenceBinder.Mapping mapping = new EvidenceBinder.Mapping(sha256(structure.text()),
                StructuralArtifactDigest.sha256(structure), structure.chunkingConfig(), template,
                compilation, List.of());
            ObjectNode sourceJson = mapper.createObjectNode();
            sourceJson.put("sourceId", sourceId);
            sourceJson.put("structuralArtifactDigest", StructuralArtifactDigest.sha256(structure));
            ArrayNode chunks = sourceJson.putArray("chunks");

            for (DocumentStructure.Chunk chunk : structure.chunks()) {
                ExtractionResult result;
                try {
                    result = adapter.extract(new TextSegment(chunk.chunkId(), chunk.text(), 0),
                        new ExtractionContext(tenant, "a2-" + sourceId, types, A2_VERSION));
                } catch (RuntimeException failure) {
                    failedChunks++;
                    ObjectNode chunkJson = chunks.addObject();
                    chunkJson.put("chunkId", chunk.chunkId());
                    chunkJson.put("error", failure.getClass().getSimpleName());
                    chunkJson.put("message", String.valueOf(failure.getMessage()));
                    continue;
                }
                quarantined += result.quarantined().size();
                if (result.llmUsage() != null) {
                    promptTokens += Math.max(0, result.llmUsage().promptTokens());
                    completionTokens += Math.max(0, result.llmUsage().completionTokens());
                }
                for (PreEntity entity : result.preEntities()) {
                    emittedTypes.merge(entity.proposedType(), 1, Integer::sum);
                    if (!types.contains(entity.proposedType())) unsupportedTypes.merge(entity.proposedType(), 1, Integer::sum);
                    EvidenceBinder.Result bound = binder.bind(structure, structure.text(), mapping,
                        new EvidenceBinder.Candidate("entity:" + entity.mentionId(),
                            EvidenceBinder.Space.STRUCTURAL_CHUNK, entity.charStart(), entity.charEnd(),
                            chunk.chunkId(), EvidenceBinder.Grounding.EXACT, template, compilation));
                    bindings.merge("entity:" + bound.status().name(), 1, Integer::sum);
                    predictedMentions.add(mentionJson(mapper, entity, sourceId, chunk.chunkId(), bound));
                }
                int ordinal = 0;
                for (RelationCandidate relation : result.relations()) {
                    emittedPredicates.merge(relation.predicate(), 1, Integer::sum);
                    if (!predicates.contains(relation.predicate())) unsupportedPredicates.merge(relation.predicate(), 1, Integer::sum);
                    EvidenceBinder.Result bound = binder.bind(structure, structure.text(), mapping,
                        new EvidenceBinder.Candidate("relation:" + sourceId + ":" + chunk.chunkId() + ":" + ordinal,
                            EvidenceBinder.Space.STRUCTURAL_CHUNK, relation.evidenceCharStart(),
                            relation.evidenceCharEnd(), relation.evidenceChunkId(),
                            EvidenceBinder.Grounding.EXACT, template, compilation));
                    bindings.merge("relation:" + bound.status().name(), 1, Integer::sum);
                    ObjectNode node = mapper.createObjectNode();
                    node.put("id", "relation:" + sourceId + ":" + chunk.chunkId() + ":" + ordinal++);
                    node.put("sourceId", sourceId);
                    node.put("predicate", relation.predicate());
                    node.put("subjectMentionId", relation.subjectMentionId());
                    node.put("objectMentionId", relation.objectMentionId());
                    node.put("status", bound.status().name());
                    node.put("reason", bound.reason());
                    if (bound.evidence() != null) {
                        node.put("documentStartOffset", bound.evidence().documentStartOffset());
                        node.put("documentEndOffset", bound.evidence().documentEndOffset());
                        node.put("grounding", bound.evidence().grounding().name());
                        node.set("elementIds", mapper.valueToTree(bound.evidence().elementIds()));
                        node.put("geometryStatus", bound.evidence().geometry().status().name());
                    }
                    predictedRelations.add(node);
                }
                ObjectNode chunkJson = chunks.addObject();
                chunkJson.put("chunkId", chunk.chunkId());
                chunkJson.put("entities", result.preEntities().size());
                chunkJson.put("relations", result.relations().size());
                chunkJson.put("integrity", result.integrity().name());
                chunkJson.put("quarantined", result.quarantined().size());
            }
            sources.add(sourceJson);
        }

        ObjectNode metrics = metrics(mapper, annotations, predictedMentions, predictedRelations);
        ObjectNode results = mapper.createObjectNode();
        results.put("runId", RUN_ID);
        results.put("a2Version", A2_VERSION);
        results.put("provider", "LOCAL/SYNTHESIZE");
        results.put("model", "synanton-qwen3-4b-synthesis");
        results.set("sources", sources);
        results.set("predictedMentions", predictedMentions);
        results.set("predictedRelations", predictedRelations);
        results.set("metrics", metrics);
        results.set("binding", mapper.valueToTree(bindings));
        results.set("emittedTypes", mapper.valueToTree(emittedTypes));
        results.set("emittedPredicates", mapper.valueToTree(emittedPredicates));
        results.set("unsupportedTypes", mapper.valueToTree(unsupportedTypes));
        results.set("unsupportedPredicates", mapper.valueToTree(unsupportedPredicates));

        ObjectNode summary = mapper.createObjectNode();
        summary.put("runtimeMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        summary.put("inputBytes", inputBytes);
        summary.put("quarantinedLines", quarantined);
        summary.put("failedChunks", failedChunks);
        summary.put("promptTokens", promptTokens);
        summary.put("completionTokens", completionTokens);
        summary.put("predictedMentions", predictedMentions.size());
        summary.put("predictedRelations", predictedRelations.size());
        summary.set("metrics", metrics);
        summary.set("binding", mapper.valueToTree(bindings));
        return new Run(results, summary);
    }

    private static ObjectNode mentionJson(ObjectMapper mapper, PreEntity entity, String sourceId,
                                          String chunkId, EvidenceBinder.Result bound) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", entity.mentionId());
        node.put("sourceId", sourceId);
        node.put("chunkId", chunkId);
        node.put("surface", entity.surfaceForm());
        node.put("type", entity.proposedType());
        node.put("status", bound.status().name());
        node.put("reason", bound.reason());
        if (bound.evidence() != null) {
            node.put("documentStartOffset", bound.evidence().documentStartOffset());
            node.put("documentEndOffset", bound.evidence().documentEndOffset());
            node.put("grounding", bound.evidence().grounding().name());
            node.set("elementIds", mapper.valueToTree(bound.evidence().elementIds()));
            node.put("geometryStatus", bound.evidence().geometry().status().name());
        }
        return node;
    }

    private static ObjectNode metrics(ObjectMapper mapper, JsonNode annotations, ArrayNode predictions,
                                      ArrayNode predictedRelations) {
        Map<String, JsonNode> a2ById = new LinkedHashMap<>();
        for (JsonNode m : annotations.path("mentions")) a2ById.put(m.path("id").asText(), m);
        List<JsonNode> a2Relations = new ArrayList<>();
        annotations.path("relations").forEach(a2Relations::add);

        Map<String, String> predToA2 = new HashMap<>();
        Set<String> usedMentions = new HashSet<>();
        int resolvedPredicted = 0;
        for (JsonNode p : predictions) {
            if (!"RESOLVED".equals(p.path("status").asText())) continue;
            resolvedPredicted++;
            String source = p.path("sourceId").asText();
            String type = p.path("type").asText();
            int start = p.path("documentStartOffset").asInt();
            int end = p.path("documentEndOffset").asInt();
            for (JsonNode a : a2ById.values()) {
                if (usedMentions.contains(a.path("id").asText())) continue;
                if (!a.path("sourceId").asText().equals(source) || !a.path("type").asText().equals(type)) continue;
                if (Math.min(end, a.path("documentEndOffset").asInt()) > Math.max(start, a.path("documentStartOffset").asInt())) {
                    predToA2.put(p.path("id").asText(), a.path("id").asText());
                    usedMentions.add(a.path("id").asText());
                    break;
                }
            }
        }

        Set<String> usedA2Relations = new HashSet<>();
        int relationTp = 0, rejectedFp = 0, unjudged = 0, unknownFp = 0;
        for (JsonNode p : predictedRelations) {
            String subject = predToA2.get(p.path("subjectMentionId").asText());
            String object = predToA2.get(p.path("objectMentionId").asText());
            if (!"RESOLVED".equals(p.path("status").asText()) || subject == null || object == null) {
                unknownFp++;
                continue;
            }
            JsonNode match = null;
            for (JsonNode r : a2Relations) {
                if (r.path("predicate").asText().equals(p.path("predicate").asText())
                    && r.path("subjectMentionId").asText().equals(subject)
                    && r.path("objectMentionId").asText().equals(object)) {
                    match = r;
                    break;
                }
            }
            if (match == null) unknownFp++;
            else if ("UNJUDGED".equals(match.path("judgment").asText())) unjudged++;
            else if ("REJECTED".equals(match.path("judgment").asText())) rejectedFp++;
            else if (usedA2Relations.add(match.path("id").asText())) relationTp++;
            else rejectedFp++;
        }
        int accepted = 0;
        for (JsonNode r : a2Relations) if ("ACCEPTED".equals(r.path("judgment").asText())) accepted++;
        int entityTp = predToA2.size();
        int entityFp = Math.max(0, resolvedPredicted - entityTp);
        int entityFn = a2ById.size() - entityTp;
        int relationFp = rejectedFp + unknownFp;
        int relationFn = accepted - relationTp;
        ObjectNode metrics = mapper.createObjectNode();
        metrics.set("entities", prf(mapper, entityTp, entityFp, entityFn));
        metrics.set("relations", prf(mapper, relationTp, relationFp, relationFn));
        ObjectNode negatives = mapper.createObjectNode();
        negatives.put("rejectedPredicted", rejectedFp);
        negatives.put("unjudgedPredicted", unjudged);
        negatives.put("unknownRelations", unknownFp);
        negatives.put("acceptedExpected", accepted);
        metrics.set("negativeAndUnjudged", negatives);
        metrics.put("a2Mentions", a2ById.size());
        metrics.put("a2Relations", a2Relations.size());
        metrics.put("predictedResolvedMentions", resolvedPredicted);
        return metrics;
    }

    private static ObjectNode prf(ObjectMapper mapper, int tp, int fp, int fn) {
        double p = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
        double r = tp + fn == 0 ? 0 : (double) tp / (tp + fn);
        double f = p + r == 0 ? 0 : 2 * p * r / (p + r);
        ObjectNode node = mapper.createObjectNode();
        node.put("tp", tp); node.put("fp", fp); node.put("fn", fn);
        node.put("precision", p); node.put("recall", r); node.put("f1", f);
        return node;
    }

    private static void write(Path path, JsonNode node, ObjectMapper mapper) throws Exception {
        Files.write(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))
                && Files.exists(current.resolve("evaluation/it-books/a2-v1/manifest.json"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    private static String arg(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length; i++) if (name.equals(args[i]) && i + 1 < args.length) return args[i + 1];
        return fallback;
    }

    private static String git(Path root, String... args) {
        try {
            List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
            command.addAll(List.of(args));
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (p.waitFor() != 0) throw new IllegalStateException(out);
            return out;
        } catch (Exception e) { throw new IllegalStateException("git failed", e); }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
}
