package org.synanton.gpu.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.synanton.gpu.gateway.config.GpuGatewayProperties;

@SpringBootApplication
@EnableConfigurationProperties(GpuGatewayProperties.class)
public class GpuGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GpuGatewayApplication.class, args);
    }
}
