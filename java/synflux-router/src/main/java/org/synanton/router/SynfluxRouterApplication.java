package org.synanton.router;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RouterProperties.class)
public class SynfluxRouterApplication {

    public static void main(String[] args) {
        SpringApplication.run(SynfluxRouterApplication.class, args);
    }
}
