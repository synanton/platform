package org.synanton.topology.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "org.synanton.topology")
@EnableScheduling
public class TopologyApplication {
    public static void main(String[] args) {
        SpringApplication.run(TopologyApplication.class, args);
    }
}
