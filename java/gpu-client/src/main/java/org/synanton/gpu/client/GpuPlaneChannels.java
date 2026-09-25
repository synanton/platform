package org.synanton.gpu.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Builds the gRPC channel to the GPU Gateway.
 *
 * <p>With TLS enabled the channel uses mTLS. The Gateway's CA ({@code ca-path}) verifies the
 * server, and the client presents {@code cert-path}/{@code key-path}. The certificate's CN is
 * the principal the Gateway authorizes for {@code tenant_id} (Deployment Plan §13; see
 * gpu-runtime doc/GPU Plane mTLS Setup.md). Missing TLS files fail closed.
 */
public final class GpuPlaneChannels {

    private static final Logger log = LoggerFactory.getLogger(GpuPlaneChannels.class);

    private GpuPlaneChannels() {}

    /**
     * @param propertyPrefix used only in error messages, e.g. {@code "gpu-plane.tls"}
     */
    public static ManagedChannel build(String endpoint, GpuPlaneClientProperties.Tls tls, String propertyPrefix) {
        String[] parts = endpoint.split(":", 2);
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9090;

        if (!tls.isEnabled()) {
            log.warn("GPU plane client uses PLAINTEXT gRPC to {} — only valid against a gateway "
                    + "in security.mode=insecure-plaintext (tests/loopback)", endpoint);
            return ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        }
        for (String[] f : new String[][]{{"ca-path", tls.getCaPath()}, {"cert-path", tls.getCertPath()},
                {"key-path", tls.getKeyPath()}}) {
            if (f[1] == null || !new File(f[1]).canRead()) {
                throw new IllegalStateException(propertyPrefix + "." + f[0] + " is not a readable file "
                        + "(mTLS is required when " + propertyPrefix + ".enabled=true)");
            }
        }
        try {
            // JDK TLS provider, not netty-tcnative: the shaded BoringSSL library is glibc-built
            // and crashes the JVM (SIGSEGV in JNI_OnLoad) on the musl/Alpine runtime images the
            // platform services use. GrpcSslContexts.forClient() would probe OpenSSL and load it.
            var ssl = GrpcSslContexts.configure(SslContextBuilder.forClient(), SslProvider.JDK)
                    .trustManager(new File(tls.getCaPath()))
                    .keyManager(new File(tls.getCertPath()), new File(tls.getKeyPath()))
                    .build();
            var builder = NettyChannelBuilder.forAddress(host, port).sslContext(ssl);
            if (tls.getAuthority() != null && !tls.getAuthority().isBlank()) {
                builder.overrideAuthority(tls.getAuthority()); // match a server-certificate SAN
            }
            return builder.build();
        } catch (javax.net.ssl.SSLException e) {
            throw new IllegalStateException("Invalid GPU gateway TLS material", e);
        }
    }
}
