package org.synanton.bench.convergence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares one baseline run against one candidate run leg by leg. Legs group
 * by (mode, filter, selectivity). Routing is frozen:
 *
 * <ul>
 *   <li>filter {@code eligibility} or {@code metadata} → eligible-set identity
 *       (exact; any break is a defect, never a caveat).
 *   <li>filter {@code none} → set-based top-K overlap vs the mode threshold.
 * </ul>
 */
public final class ConvergenceRunner {

    private ConvergenceRunner() {}

    public record LegVerdict(
            String mode,
            String filter,
            String selectivity,
            String check,
            double value,
            double threshold,
            boolean pass,
            String detail) {}

    public record Report(List<LegVerdict> legs, boolean overallPass) {}

    public static Report compare(RunOutput baseline, RunOutput candidate, ConvergenceConfig config) {
        if (!baseline.corpus().equals(candidate.corpus())) {
            throw new IllegalArgumentException(
                    "corpus mismatch: baseline=" + baseline.corpus() + " candidate=" + candidate.corpus()
                            + " — same corpus or no comparison");
        }
        Map<String, QueryResult> candidateByLeg = index(candidate);
        List<LegVerdict> verdicts = new ArrayList<>();
        for (QueryResult base : baseline.queries()) {
            String key = legKey(base);
            QueryResult cand = candidateByLeg.get(key);
            if (cand == null) {
                throw new IllegalArgumentException(
                        "candidate run '" + candidate.runId() + "' missing leg " + key
                                + " — legs must match exactly");
            }
            verdicts.add(compareLeg(base, cand, config));
        }
        for (QueryResult cand : candidate.queries()) {
            if (baseline.queries().stream().noneMatch(b -> legKey(b).equals(legKey(cand)))) {
                throw new IllegalArgumentException(
                        "candidate run has extra leg " + legKey(cand) + " absent from baseline");
            }
        }
        boolean overall = verdicts.stream().allMatch(LegVerdict::pass);
        return new Report(List.copyOf(verdicts), overall);
    }

    private static LegVerdict compareLeg(QueryResult base, QueryResult cand, ConvergenceConfig config) {
        boolean identityLeg = base.filter().equals("eligibility") || base.filter().equals("metadata");
        if (identityLeg) {
            boolean pass = LegComparator.eligibleSetsIdentical(base.eligibleSet(), cand.eligibleSet());
            return new LegVerdict(
                    base.mode(), base.filter(), base.selectivity(),
                    "eligible-set-identity", pass ? 1.0 : 0.0, 1.0, pass,
                    pass ? "identical (" + base.eligibleSet().size() + " ids)"
                            : "MISMATCH baseline=" + base.eligibleSet().size()
                                    + " candidate=" + cand.eligibleSet().size() + " — defect, not caveat");
        }
        double overlap = LegComparator.overlap(base.topK(), cand.topK());
        double threshold = config.thresholdFor(base.mode());
        boolean pass = overlap >= threshold;
        return new LegVerdict(
                base.mode(), base.filter(), base.selectivity(),
                "topk-overlap", overlap, threshold, pass,
                String.format("overlap=%.3f threshold=%.2f", overlap, threshold));
    }

    private static Map<String, QueryResult> index(RunOutput run) {
        Map<String, QueryResult> map = new LinkedHashMap<>();
        run.queries().forEach(q -> map.put(legKey(q), q));
        return map;
    }

    private static String legKey(QueryResult q) {
        return q.queryId() + "|" + q.mode() + "|" + q.filter() + "|" + q.selectivity();
    }
}
