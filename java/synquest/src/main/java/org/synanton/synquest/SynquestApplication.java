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

    @Bean
    ApplicationRunner indexInitializer(SearchService searchService) {
        return args -> {
            // Run in a background thread so the server starts accepting requests immediately.
            Thread.ofVirtual().name("index-init").start(() -> searchService.initTenant("demo"));
        };
    }
}
