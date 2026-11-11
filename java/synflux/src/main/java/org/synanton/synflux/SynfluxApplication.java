package org.synanton.synflux;

import org.synanton.common.runtime.NativeTransportProbe;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
    "org.synanton.synflux",
    "org.synanton.synvault",
    "org.synanton.ingestioncache"
})
@EnableScheduling
public class SynfluxApplication {
    public static void main(String[] args) {
        NativeTransportProbe.logStatus();
        SpringApplication.run(SynfluxApplication.class, args);
    }
}
