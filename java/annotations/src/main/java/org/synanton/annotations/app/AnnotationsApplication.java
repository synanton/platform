package org.synanton.annotations.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
    "org.synanton.annotations",
    "org.synanton.ingestioncache"
})
public class AnnotationsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnnotationsApplication.class, args);
    }
}
