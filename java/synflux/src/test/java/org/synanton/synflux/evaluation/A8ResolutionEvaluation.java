package org.synanton.synflux.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.synanton.synflux.resolution.CanonicalCatalog;
import org.synanton.synflux.resolution.CanonicalConcept;
import org.synanton.synflux.resolution.DeterministicEntityResolver;
import org.synanton.synflux.resolution.MentionRef;
import org.synanton.synflux.resolution.ResolutionResult;
import org.synanton.synflux.resolution.ResolutionStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A8.1: resolve A4.1 candidates against an independent canonical catalog. No graph writes. */
public final class A8ResolutionEvaluation {
    private static final String RUN_ID = "it-books-a8-resolution-v1";

    private A8ResolutionEvaluation() {}

    public static void main(String[] args) throws Exception {
        Path root = repositoryRoot();
        Path a4 = Path.of(argument(args, "--a4-dir",
            root.resolve("evaluation/it-books/p3b-p4/a4-inspection-v1").toString()));
        Path a8 = Path.of(argument(args, "--a8-dir",
            root.resolve("evaluation/it-books/a8-v1").toString()));
        Path out = Path.of(argument(args, "--out", outDefault(root).toString()));
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

        JsonNode candidates = mapper.readTree(a4.resolve("candidates.json").toFile());
        JsonNode catalogJson = mapper.readTree(a8.resolve("catalog.json").toFile());
        JsonNode judgmentsJson = mapper.readTree(a8.resolve("judgments.json").toFile());
        List<CanonicalConcept> concepts = new ArrayList<>();
        catalogJson.path("concepts").forEach(node -> concepts.add(new CanonicalConcept(
            node.path("canonicalId").asText(), node.path("label").asText(),
            node.path("type").asText(), strings(node.path("aliases")))));
        CanonicalCatalog catalog = new CanonicalCatalog(
            catalogJson.path("version").asText(), concepts);
        DeterministicEntityResolver resolver = new DeterministicEntityResolver(catalog);
        Map<String, JsonNode> judgments = new HashMap<>();
        judgmentsJson.path("mentions").forEach(node -> judgments.put(node.path("mentionId").asText(), node));

        ArrayNode results = mapper.createArrayNode();
        int resolved = 0;
        int unresolved = 0;
        int ambiguous = 0;
        int typeMismatch = 0;
        int reviewed = 0;
        int reviewedResolved = 0;
        int reviewedResolvedCorrect = 0;
        int reviewedOutcomeCorrect = 0;
        int identityEligible = 0;
        int identityCorrect = 0;
        int reviewedTypeMismatch = 0;
        int reviewedAmbiguous = 0;
        for (JsonNode candidate : candidates.path("mentions")) {
            MentionRef mention = new MentionRef(candidate.path("mentionId").asText(),
                candidate.path("surface").asText(), candidate.path("type").asText());
            ResolutionResult result = resolver.resolve(mention);
            switch (result.status()) {
                case RESOLVED -> resolved++;
                case UNRESOLVED -> unresolved++;
                case AMBIGUOUS -> ambiguous++;
                case TYPE_MISMATCH -> typeMismatch++;
            }
            ObjectNode node = results.addObject();
            node.put("mentionId", candidate.path("mentionId").asText());
            node.put("sourceId", candidate.path("sourceId").asText());
            node.put("surface", candidate.path("surface").asText());
            node.put("proposedType", candidate.path("type").asText());
            node.put("documentStartOffset", candidate.path("documentStartOffset").asInt());
            node.put("documentEndOffset", candidate.path("documentEndOffset").asInt());
            node.put("status", result.status().name());
            node.put("canonicalId", result.canonicalId());
            node.put("canonicalLabel", result.canonicalLabel());
            node.put("matchMethod", result.matchMethod());
            node.put("reason", result.reason());
            node.put("normalizedSurface", result.normalizedSurface());
            node.put("strategyVersion", result.strategyVersion());
            node.put("catalogVersion", result.catalogVersion());
            node.put("catalogDigest", result.catalogDigest());

            JsonNode judgment = judgments.get(result.mentionId());
            if (judgment != null) {
                reviewed++;
                boolean outcomeCorrect = judgment.path("expectedStatus").asText().equals(result.status().name());
                if (outcomeCorrect) reviewedOutcomeCorrect++;
                if (result.status() == ResolutionStatus.RESOLVED) reviewedResolved++;
                if (judgment.path("expectedStatus").asText().equals("RESOLVED")) {
                    identityEligible++;
                    if (result.status() == ResolutionStatus.RESOLVED
                        && judgment.path("expectedCanonicalId").asText().equals(result.canonicalId())) {
                        identityCorrect++;
                        reviewedResolvedCorrect++;
                    }
                }
                if (result.status() == ResolutionStatus.TYPE_MISMATCH) reviewedTypeMismatch++;
                if (result.status() == ResolutionStatus.AMBIGUOUS) reviewedAmbiguous++;
                node.put("expectedStatus", judgment.path("expectedStatus").asText());
                node.put("expectedCanonicalId", judgment.path("expectedCanonicalId").asText(null));
                node.put("outcomeCorrect", outcomeCorrect);
            }
        }

        ObjectNode metrics = mapper.createObjectNode();
        metrics.put("candidates", candidates.path("mentions").size());
        metrics.put("resolved", resolved);
        metrics.put("unresolved", unresolved);
        metrics.put("ambiguous", ambiguous);
        metrics.put("typeMismatch", typeMismatch);
        metrics.put("resolutionCoverage", ratio(resolved, candidates.path("mentions").size()));
        metrics.put("ambiguityRate", ratio(ambiguous, candidates.path("mentions").size()));
        metrics.put("typeMismatchRate", ratio(typeMismatch, candidates.path("mentions").size()));
        metrics.put("reviewedCandidates", reviewed);
        metrics.put("unjudgedCandidates", candidates.path("mentions").size() - reviewed);
        metrics.put("reviewedResolved", reviewedResolved);
        metrics.put("reviewedResolvedCorrect", reviewedResolvedCorrect);
        metrics.put("reviewedOutcomeCorrect", reviewedOutcomeCorrect);
        metrics.put("reviewedPrecision", ratio(reviewedResolvedCorrect, reviewedResolved));
        metrics.put("reviewedRecall", ratio(identityCorrect, identityEligible));
        metrics.put("identityEligibleReviewed", identityEligible);
        metrics.put("reviewedTypeMismatch", reviewedTypeMismatch);
        metrics.put("reviewedAmbiguous", reviewedAmbiguous);
        metrics.put("metricNote", "precision/recall use only independently judged candidates; unjudged candidates remain in coverage and are not filtered");

        ObjectNode report = mapper.createObjectNode();
        report.put("runId", RUN_ID);
        report.put("a4CandidatesSha256", sha256(Files.readAllBytes(a4.resolve("candidates.json"))));
        report.put("catalogVersion", catalog.version());
        report.put("catalogDigest", catalog.digest());
        report.put("judgmentsVersion", judgmentsJson.path("version").asText());
        report.put("strategyVersion", DeterministicEntityResolver.STRATEGY_VERSION);
        report.put("productionGraphWrites", false);
        report.set("metrics", metrics);
        report.set("results", results);
        Path metricsPath = out.resolve("results.json");
        Files.createDirectories(out);
        Files.write(metricsPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));

        ObjectNode run = mapper.createObjectNode();
        run.put("runId", RUN_ID);
        run.put("gitCommit", git(root, "rev-parse", "HEAD"));
        run.put("a4CandidatesSha256", report.path("a4CandidatesSha256").asText());
        run.put("catalogDigest", catalog.digest());
        run.put("judgmentsSha256", sha256(Files.readAllBytes(a8.resolve("judgments.json"))));
        run.put("resultsSha256", sha256(Files.readAllBytes(metricsPath)));
        run.put("productionGraphWrites", false);
        run.put("llmUsed", false);
        run.put("gpuUsed", false);
        Files.write(out.resolve("run-manifest.json"),
            mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(run));
        System.out.println("wrote " + out + " coverage=" + metrics.path("resolutionCoverage").asDouble()
            + " reviewedPrecision=" + metrics.path("reviewedPrecision").asDouble()
            + " reviewedRecall=" + metrics.path("reviewedRecall").asDouble());
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static String argument(String[] args, String name, String fallback) {
        for (int i = 0; i + 1 < args.length; i++) if (name.equals(args[i])) return args[i + 1];
        return fallback;
    }

    private static Path outDefault(Path root) {
        return root.resolve("build/a8-resolution-evaluation");
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))
                && Files.exists(current.resolve("evaluation/it-books/a8-v1/catalog.json"))) {
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

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
