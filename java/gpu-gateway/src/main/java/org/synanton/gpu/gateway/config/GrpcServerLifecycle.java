package org.synanton.gpu.gateway.config;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.synanton.gpu.gateway.GpuExecutionServiceImpl;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private volatile Server server;

    private final GpuGatewayProperties properties;
    private final GpuExecutionServiceImpl service;

    public GrpcServerLifecycle(GpuGatewayProperties properties, GpuExecutionServiceImpl service) {
        this.properties = properties;
        this.service = service;
    }

    @Override
    public void start() {
        try {
            server = NettyServerBuilder
                    .forPort(properties.getGrpcPort())
                    .maxInboundMessageSize(properties.getMaxInboundMessageSizeBytes())
                    .addService(service)
                    .build()
                    .start();
            log.info("gRPC server started on port {}", properties.getGrpcPort());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start gRPC server on port " + properties.getGrpcPort(), e);
        }
    }

    @Override
    public void stop() {
        if (server != null && !server.isShutdown()) {
            log.info("Shutting down gRPC server");
            server.shutdown();
            try {
                if (!server.awaitTermination(30, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
