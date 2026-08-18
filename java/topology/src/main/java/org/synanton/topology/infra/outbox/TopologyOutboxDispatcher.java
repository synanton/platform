package org.synanton.topology.infra.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TopologyOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TopologyOutboxDispatcher.class);

    private final JdbcTemplate jdbc;

    public TopologyOutboxDispatcher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${topology.outbox.poll-interval-ms:5000}")
    public void dispatch() {
        // In Phase 3, Kafka is not yet wired - just mark rows as dispatched after logging.
        // Phase 4 will wire the actual Kafka producer here.
        var rows = jdbc.queryForList(
                "SELECT event_id, event_type, payload FROM topology.topology_outbox WHERE dispatched = FALSE ORDER BY created_at LIMIT 100"
        );
        if (rows.isEmpty()) return;
        log.info("TopologyOutboxDispatcher: {} pending events (Kafka not yet wired in Phase 3)", rows.size());
        for (var row : rows) {
            try {
                // TODO Phase 4: produce to Kafka topology_events topic
                log.debug("Outbox event type={} payload={}", row.get("event_type"), row.get("payload"));
                jdbc.update(
                        "UPDATE topology.topology_outbox SET dispatched = TRUE, dispatched_at = now() WHERE event_id = ?",
                        row.get("event_id")
                );
            } catch (Exception e) {
                log.error("Failed to dispatch outbox event {}", row.get("event_id"), e);
            }
        }
    }
}
