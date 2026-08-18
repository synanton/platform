package org.synanton.planner.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class EntityLabelIndex {
    private static final Logger log = LoggerFactory.getLogger(EntityLabelIndex.class);

    private final RestClient relixClient;
    private final AtomicReference<Set<String>> labels = new AtomicReference<>(Set.of());
    private final AtomicLong lastRefresh = new AtomicLong(0);

    public EntityLabelIndex(org.synanton.planner.config.PlannerProperties props) {
        this.relixClient = RestClient.builder()
            .baseUrl(props.relix().baseUrl())
            .build();
    }

    @Scheduled(fixedDelayString = "${planner.labels.refresh-interval-seconds:300}000")
    public void refresh() {
        try {
            String[] arr = relixClient.get().uri("/entities/labels").retrieve()
                .body(String[].class);
            if (arr != null) {
                labels.set(new HashSet<>(Arrays.asList(arr)));
                lastRefresh.set(System.currentTimeMillis());
                log.info("Entity label index refreshed: {} labels", arr.length);
            }
        } catch (Exception e) {
            log.warn("Failed to refresh entity labels from relix: {}", e.getMessage());
        }
    }

    public Set<String> getLabels() { return labels.get(); }

    public List<String> withPrefix(String prefix) {
        String lower = prefix.toLowerCase();
        return labels.get().stream()
            .filter(l -> l.toLowerCase().startsWith(lower))
            .limit(20)
            .toList();
    }

    public long getLastRefresh() { return lastRefresh.get(); }
}
