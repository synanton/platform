package org.synanton.bench.convergence;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Typed view over {@code 028-convergence-config.yaml}. Tolerances are pinned
 * before running, never after — this class only reads them.
 */
public record ConvergenceConfig(
        List<String> legs,
        double lexicalTopKOverlap,
        double vectorTopKOverlap,
        double hybridTopKOverlap,
        boolean minScoreNeutral) {

    @SuppressWarnings("unchecked")
    public static ConvergenceConfig parse(InputStream in) throws IOException {
        Map<String, Object> root = new Yaml().load(in);
        if (root == null) {
            throw new IOException("empty convergence config");
        }
        List<String> legs = (List<String>) root.getOrDefault("legs", List.of());
        Map<String, Object> tolerances = (Map<String, Object>) root.get("tolerances");
        if (tolerances == null) {
            throw new IOException("convergence config missing 'tolerances'");
        }
        return new ConvergenceConfig(
                List.copyOf(legs),
                number(tolerances, "lexical_topk_overlap"),
                number(tolerances, "vector_topk_overlap"),
                number(tolerances, "hybrid_topk_overlap"),
                true);
    }

    private static double number(Map<String, Object> map, String key) throws IOException {
        Object v = map.get(key);
        if (!(v instanceof Number)) {
            throw new IOException("convergence config tolerance '" + key + "' missing or not numeric");
        }
        return ((Number) v).doubleValue();
    }

    /** Overlap threshold for a search mode; unknown modes fail loudly. */
    public double thresholdFor(String mode) {
        return switch (mode) {
            case "lexical" -> lexicalTopKOverlap();
            case "vector" -> vectorTopKOverlap();
            case "hybrid" -> hybridTopKOverlap();
            default -> throw new IllegalArgumentException("unknown search mode: " + mode);
        };
    }
}
