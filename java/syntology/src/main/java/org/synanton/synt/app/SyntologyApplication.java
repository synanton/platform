package org.synanton.synt.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "org.synanton.synt")
@EnableScheduling
public class SyntologyApplication {

    public static void main(String[] args) {
        SpringApplication.run(SyntologyApplication.class, args);
    }
}
