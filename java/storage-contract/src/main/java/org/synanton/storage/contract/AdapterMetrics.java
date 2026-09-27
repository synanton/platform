package org.synanton.storage.contract;

/**
 * Metrics port every adapter exposes (§8.3, YDB-POC-038). Adapters record each
 * port operation with latency and outcome; deployments scrape
 * {@link #snapshot()} on their own schedule. Names are provider-neutral —
 * backends differ behind the port, never in the taxonomy.
 */
public interface AdapterMetrics {

    /** Operation verbs. New verbs require a taxonomy update, not an ad-hoc string. */
    String SYNVAULT_PUT = "synvault.put";
    String SYNVAULT_GET = "synvault.get";
    String SYNVAULT_DELETE = "synvault.delete";
    String SYNVAULT_CHUNKS = "synvault.chunks";
    String SYNVAULT_REVISION = "synvault.revision";
    String SYNVAULT_PROVENANCE = "synvault.provenance";
    String SYNQUEST_SEARCH = "synquest.search";
    String SYNQUEST_UPSERT = "synquest.upsert";
    String SYNQUEST_DELETE = "synquest.delete";
    String SYNQUEST_REBUILD = "synquest.rebuild";

    void record(String operation, long latencyNanos, boolean success);

    AdapterStats snapshot();

    /** Timing helper: {@code try (Timing t = metrics.time(OP)) { ...; t.success(); } }. */
    default Timing time(String operation) {
        return new Timing(this, operation, System.nanoTime());
    }

    final class Timing implements AutoCloseable {
        private final AdapterMetrics metrics;
        private final String operation;
        private final long start;
        private boolean success;

        Timing(AdapterMetrics metrics, String operation, long start) {
            this.metrics = metrics;
            this.operation = operation;
            this.start = start;
        }

        public void success() {
            this.success = true;
        }

        @Override
        public void close() {
            metrics.record(operation, System.nanoTime() - start, success);
        }
    }
}
