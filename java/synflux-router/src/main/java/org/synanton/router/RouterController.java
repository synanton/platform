package org.synanton.router;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/router")
public class RouterController {

    private final RouterConsumer consumer;

    public RouterController(RouterConsumer consumer) {
        this.consumer = consumer;
    }

    @GetMapping("/status")
    public RouterStatus status() {
        Map<String, RouterStatus.TopicInfo> topics = Map.of(
                "ingestion_requests", new RouterStatus.TopicInfo(4, totalLag("ingestion_requests")),
                "ingestion_events", new RouterStatus.TopicInfo(4, totalLag("ingestion_events"))
        );
        return new RouterStatus(topics, consumer.getPausedTenants());
    }

    @PostMapping("/pause/{tenantId}")
    public ResponseEntity<Void> pause(@PathVariable String tenantId) {
        consumer.pause(tenantId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/resume/{tenantId}")
    public ResponseEntity<Void> resume(@PathVariable String tenantId) {
        consumer.resume(tenantId);
        return ResponseEntity.ok().build();
    }

    private long totalLag(String topicPrefix) {
        return consumer.getLagSnapshot().entrySet().stream()
                .filter(e -> e.getKey().startsWith(topicPrefix))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }
}
