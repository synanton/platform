package org.synanton.bench.corpus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 028a.6 golden queries (spec §4 + §5): 120 queries, modes cycling
 * lexical/vector/hybrid (40 each), filters cycling none/tenant/metadata/
 * eligibility (30 each). Query <i>i</i> uses cluster <i>i mod 120</i>.
 *
 * <p>Query vectors are the c_q-adjacent centroids computed through the SAME
 * pure function .5 uses ({@link ChunkEmbedding#centroid} over the 5 relevant
 * chunk embeddings) — never an independent recompute, so bytes are identical
 * by construction (verified in test, not hoped).
 *
 * <p>Eligible sets follow spec §5 derivation (scope bounds; predicates never
 * widen). Tier labels are nearest-target names; exact counts ship (Gap B:
 * targets don't gate).
 */
public record GoldenQuery(
        String queryId,
        String mode,
        String text,
        String queryVectorB64,
        String filterKind,
        List<String> tenantScope,
        Map<String, String> metadataPredicate,
        String selectivity,
        List<UUID> relevantChunkIds,
        List<UUID> eligibleChunkIds) {

    public static final int QUERIES = 120;

    private static final String[] MODES = {"lexical", "vector", "hybrid"};
    private static final String[] FILTERS = {"none", "tenant", "metadata", "eligibility"};

    public static List<GoldenQuery> generate(List<ChunkRecord> chunks, ChunkEmbedding embedding) {
        // Indexes for eligible-set derivation (single pass over the corpus).
        Map<String, List<ChunkRecord>> byTenant = new LinkedHashMap<>();
        chunks.forEach(c -> byTenant.computeIfAbsent(c.tenantId(), k -> new ArrayList<>()).add(c));
        // filter=none eligible set is identical for every none-leg: build once,
        // share the reference (30 × 160k UUIDs would otherwise 5× memory).
        List<UUID> allIds = chunks.stream().map(ChunkRecord::chunkId).sorted().toList();

        // Tier scopes: smallest tenant sets whose share brackets each target
        // (nearest-target labels; exact counts ship).
        List<TierScope> tiers = pickTiers(byTenant, chunks.size());

        List<GoldenQuery> out = new ArrayList<>(QUERIES);
        for (int i = 0; i < QUERIES; i++) {
            int cluster = i % ChunkRecord.QUERIES;
            String mode = MODES[i % MODES.length];
            String filter = FILTERS[(i / MODES.length) % FILTERS.length];
            out.add(build(i, cluster, mode, filter, tiers, byTenant, chunks, allIds, embedding));
        }
        return List.copyOf(out);
    }

    private record TierScope(String label, List<String> tenants, Map<String, String> pred) {}

    /**
     * Tier scopes: search tenant × single-attribute combos for the share
     * nearest each target in log space. Single tenants alone cannot reach
     * 0.1% (smallest tenant_49 ≈ 0.44%); metadata trims close the gap.
     * Labels are nearest-target names; exact counts ship (targets don't gate).
     */
    private static List<TierScope> pickTiers(Map<String, List<ChunkRecord>> byTenant, int total) {
        List<Map<String, String>> preds = new ArrayList<>();
        preds.add(Map.of());
        for (String t : ChunkRecord.DOC_TYPES) {
            preds.add(Map.of("doc_type", t));
        }
        for (String l : ChunkRecord.LANGS) {
            preds.add(Map.of("lang", l));
        }
        for (String s : ChunkRecord.SENSITIVITIES) {
            preds.add(Map.of("sensitivity", s));
        }
        String[] targets = {"0.1%", "1%", "10%", "100%"};
        double[] wanted = {0.001, 0.01, 0.10, 1.0};
        List<TierScope> tiers = new ArrayList<>();
        List<String> all = new ArrayList<>(byTenant.keySet());
        tiers.add(new TierScope("100%", List.copyOf(all), Map.of()));
        for (int t = 0; t < 3; t++) {
            TierScope best = null;
            double bestErr = Double.MAX_VALUE;
            for (String tenant : all) {
                for (Map<String, String> pred : preds) {
                    int n = eligibleForScope(List.of(tenant), pred, byTenant).size();
                    double err = Math.abs(Math.log((n / (double) total) / wanted[t]));
                    if (err < bestErr) {
                        bestErr = err;
                        best = new TierScope(targets[t], List.of(tenant), pred);
                    }
                }
            }
            tiers.add(best);
        }
        return List.copyOf(tiers);
    }

    private static GoldenQuery build(
            int i,
            int cluster,
            String mode,
            String filter,
            List<TierScope> tiers,
            Map<String, List<ChunkRecord>> byTenant,
            List<ChunkRecord> chunks,
            List<UUID> allIds,
            ChunkEmbedding embedding) {
        // Relevant = the 5 planted chunk ids, derived from placement (not text
        // scan): exact by construction, O(1) per query.
        List<UUID> relevant =
                ChunkRecord.plantedIndices(cluster).stream()
                        .map(g -> chunks.get(g).chunkId())
                        .sorted()
                        .toList();
        if (relevant.size() != ChunkRecord.PLANTED_PER_QUERY) {
            throw new IllegalStateException("cluster " + cluster + " has " + relevant.size());
        }
        // Query vector = centroid of the 5 relevant chunk embeddings, same
        // function .5 uses — byte-identical by construction.
        List<double[]> vectors = new ArrayList<>();
        for (int g : ChunkRecord.plantedIndices(cluster)) {
            ChunkRecord c = chunks.get(g);
            vectors.add(ChunkEmbedding.decode(embedding.embed(c.text(), c.docIndex(), c.ordinal())));
        }
        String qvec = ChunkEmbedding.encode(ChunkEmbedding.centroid(vectors));

        List<String> scope = List.of();
        Map<String, String> pred = Map.of();
        String selectivity = "-";
        List<UUID> eligible;
        switch (filter) {
            case "none" -> eligible = allIds;
            case "tenant" -> {
                scope = tenantFixture(i);
                eligible = eligibleForScope(scope, pred, byTenant);
            }
            case "metadata" -> {
                scope = tenantFixture(i);
                pred = Map.of("doc_type", ChunkRecord.DOC_TYPES[i % ChunkRecord.DOC_TYPES.length]);
                eligible = eligibleForScope(scope, pred, byTenant);
            }
            case "eligibility" -> {
                // Tier rotation decoupled from the filter rotation: eligibility
                // blocks start at i=9,21,33,… (every 12), so (i/12)%4 cycles all
                // four tiers (i%4 alone provably never hits tiers[0] there).
                TierScope tier = tiers.get((i / 12) % tiers.size());
                scope = tier.tenants();
                pred = tier.pred();
                selectivity = tier.label();
                eligible = eligibleForScope(scope, pred, byTenant);
            }
            default -> throw new IllegalArgumentException(filter);
        }
        String text =
                (mode.equals("vector"))
                        ? ""
                        : String.join(" ", ChunkRecord.plantedTerms(cluster));
        return new GoldenQuery(
                String.format("q%03d", i), mode, text, qvec, filter, scope, pred,
                selectivity, relevant, eligible);
    }

    private static List<String> tenantFixture(int i) {
        return switch (i % 4) {
            case 0 -> List.of("tenant_07");
            case 1 -> List.of("tenant_07", "tenant_11");
            case 2 -> List.of("tenant_00");
            default -> List.of("tenant_49");
        };
    }

    private static List<UUID> eligibleForScope(
            List<String> scope, Map<String, String> pred, Map<String, List<ChunkRecord>> byTenant) {
        List<UUID> out = new ArrayList<>();
        for (String tenant : scope) {
            for (ChunkRecord c : byTenant.getOrDefault(tenant, List.of())) {
                if (pred.entrySet().stream()
                        .allMatch(e -> e.getValue().equals(metadataValue(c, e.getKey())))) {
                    out.add(c.chunkId());
                }
            }
        }
        out.sort(null);
        return List.copyOf(out);
    }

    private static String metadataValue(ChunkRecord c, String key) {
        return switch (key) {
            case "doc_type" -> c.docType();
            case "lang" -> c.lang();
            case "sensitivity" -> c.sensitivity();
            default -> throw new IllegalArgumentException(key);
        };
    }
}
