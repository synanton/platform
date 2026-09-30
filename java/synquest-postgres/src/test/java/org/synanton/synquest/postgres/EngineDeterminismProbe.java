package org.synanton.synquest.postgres;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.synanton.storage.contract.PolicyContext;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.TenantScope;
import org.synanton.synquest.api.EligibilityConstraints;
import org.synanton.synquest.api.RelevanceFilters;
import org.synanton.synquest.api.SearchMode;
import org.synanton.synquest.api.SearchRequest;
import org.synanton.synquest.api.SearchResult;
import org.synanton.synquest.api.TemporalExtension;

/**
 * Cross-process determinism probe (007-7b, 028a.8 pattern): runs a fixed
 * query set against the shared container and prints one line per hit,
 * {@code mode|query|chunkId|score}. No JUnit — launched via {@code java}
 * with different heap flags; the parent compares stdout byte-for-byte.
 * Scores print via {@link Double#toString} (exact round-trip).
 *
 * <p>Args: {@code jdbcUrl user password tenant}.
 */
public final class EngineDeterminismProbe {

    public static void main(String[] args) throws Exception {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(args[0]);
        ds.setUser(args[1]);
        ds.setPassword(args[2]);
        String tenant = args[3];
        PostgresSynquestEngine engine = new PostgresSynquestEngine(ds);
        TenantScope scope = TenantScope.of(tenant);
        PolicyContext policy = PolicyContext.of("p", "r1");
        SecurityContext ctx = SecurityContext.user(scope, PrincipalRef.user("u-1"), policy);
        EligibilityConstraints eligibility =
                EligibilityConstraints.from(scope, List.of(PrincipalRef.user("u-1")), policy);

        print(engine, ctx, "lexical",
                new SearchRequest("determinism alpha", Optional.empty(), Optional.empty(),
                        SearchMode.LEXICAL, eligibility, RelevanceFilters.none(),
                        TemporalExtension.empty(), 10, 0.0));
        print(engine, ctx, "vector",
                new SearchRequest("ignored", Optional.of(new float[] {1.0f, 0.0f}), Optional.empty(),
                        SearchMode.VECTOR, eligibility, RelevanceFilters.none(),
                        TemporalExtension.empty(), 10, -10.0));
        print(engine, ctx, "hybrid",
                new SearchRequest("determinism alpha", Optional.of(new float[] {1.0f, 0.0f}),
                        Optional.empty(), SearchMode.HYBRID, eligibility, RelevanceFilters.none(),
                        TemporalExtension.empty(), 10, 0.0));
    }

    private static void print(
            PostgresSynquestEngine engine, SecurityContext ctx, String mode, SearchRequest request)
            throws Exception {
        SearchResult result = engine.search(ctx, request).toCompletableFuture().join();
        System.out.println(mode + "|total|" + result.totalEligible());
        for (var hit : result.hits()) {
            System.out.println(
                    mode + "|" + hit.chunkId().value() + "|" + Double.toString(hit.score()));
        }
    }
}
