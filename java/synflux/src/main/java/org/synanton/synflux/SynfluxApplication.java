package org.synanton.synflux;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
    "org.synanton.synflux",
    "org.synanton.synvault",
    "org.synanton.ingestioncache"
})
public class SynfluxApplication {
    public static void main(String[] args) {
        SpringApplication.run(SynfluxApplication.class, args);
    }
}
