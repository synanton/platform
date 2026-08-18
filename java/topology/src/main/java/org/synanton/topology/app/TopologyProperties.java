package org.synanton.topology.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "topology")
public record TopologyProperties(
        String ontologyDir,
        Outbox outbox,
        HighSecurity highSecurity,
        Regions regions
) {
    public record Outbox(int pollIntervalMs, int dispatchIntervalMs) {}

    public record HighSecurity(int ackDeadlineMs, int reconcilerMaxAttempts) {}

    public record Regions(List<String> registered) {}

    public TopologyProperties {
        if (outbox == null) {
            outbox = new Outbox(5000, 100);
        }
        if (highSecurity == null) {
            highSecurity = new HighSecurity(50, 60);
        }
        if (regions == null) {
            regions = new Regions(List.of("us-east-1", "us-west-2", "eu-west-1", "ap-southeast-1"));
        }
    }
}
