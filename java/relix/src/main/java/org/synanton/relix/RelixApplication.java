package org.synanton.relix;

import org.synanton.relix.service.GraphQueryService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = {
        "org.synanton.relix",
        "org.synanton.ingestioncache"
})
public class RelixApplication {

    public static void main(String[] args) {
        SpringApplication.run(RelixApplication.class, args);
    }

    @Bean
    ApplicationRunner graphInitializer(GraphQueryService graphQueryService) {
        return args -> graphQueryService.loadTenant("demo");
    }
}
