package org.synanton.synquest;

import org.synanton.synquest.service.SearchService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = {
        "org.synanton.synquest",
        "org.synanton.ingestioncache"
})
public class SynquestApplication {

    public static void main(String[] args) {
        SpringApplication.run(SynquestApplication.class, args);
    }

    /**
     * Optional demo seed: initialise the named tenant's index at startup.
     * Disabled unless {@code synquest.seed.demo-tenant} is set explicitly — the
     * previous unconditional {@code initTenant("demo")} seeded demo state in
     * every deployment.
     */
    @Bean
    ApplicationRunner indexInitializer(SearchService searchService,
            @org.springframework.beans.factory.annotation.Value("${synquest.seed.demo-tenant:}") String seedTenant) {
        return args -> {
            if (seedTenant == null || seedTenant.isBlank()) {
                return;
            }
            // Run in a background thread so the server starts accepting requests immediately.
            Thread.ofVirtual().name("index-init").start(() -> searchService.initTenant(seedTenant));
        };
    }
}
