package org.synanton.synvault;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"org.synanton.synvault", "org.synanton.ingestioncache"})
public class SynvaultApplication {
    public static void main(String[] args) {
        SpringApplication.run(SynvaultApplication.class, args);
    }
}
