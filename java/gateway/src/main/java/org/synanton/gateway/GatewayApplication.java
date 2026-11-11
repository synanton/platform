package org.synanton.gateway;

import org.synanton.common.runtime.NativeTransportProbe;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        NativeTransportProbe.logStatus();
        SpringApplication.run(GatewayApplication.class, args);
    }
}
