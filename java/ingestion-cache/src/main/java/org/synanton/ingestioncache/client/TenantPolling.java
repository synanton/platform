package org.synanton.ingestioncache.client;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.synanton.ingestioncache.claim.ClaimService;

/** Resolves the bounded set of tenants a poller should serve this cycle. */
public final class TenantPolling {

    public static final String ALL = "*";

    private TenantPolling() {
    }

    /**
     * A configured tenant keeps single-tenant behaviour. Blank or {@code *}
     * switches to authoritative registry enumeration.
     */
    public static List<String> resolve(String configuredTenant, IngestionCacheClient cache) {
        return resolve(configuredTenant, cache::listTenantIds);
    }

    public static List<String> resolve(String configuredTenant, ClaimService claims) {
        return resolve(configuredTenant, claims::listTenantIds);
    }

    /**
     * Runs each tenant on its own virtual thread and waits for all of them.
     * A failing tenant is isolated; its exception is rethrown after the others
     * finish so the scheduler still observes the failure.
     */
    public static void forEachConcurrent(List<String> tenants, Consumer<String> action) {
        if (tenants.isEmpty()) {
            return;
        }
        RuntimeException failure = null;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (String tenant : tenants) {
                futures.add(executor.submit(() -> action.accept(tenant)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    if (failure == null) {
                        failure = e instanceof RuntimeException runtime
                                ? runtime : new IllegalStateException("tenant poll failed", e);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static List<String> resolve(String configuredTenant,
                                        java.util.function.Supplier<List<String>> tenantSource) {
        if (configuredTenant != null) {
            String tenant = configuredTenant.trim();
            if (!tenant.isEmpty() && !ALL.equals(tenant)) {
                return List.of(tenant);
            }
        }
        return tenantSource.get();
    }
}
