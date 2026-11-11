# ADR-024: glibc-based runtime images for native dependencies

**Status:** Accepted — 2026-11-11
**Basis:** gRPC/Netty native transports and other JNI/JNA libraries ship glibc-linked binaries; Alpine uses musl libc. See issue #603 and activation gate #589.

## Decision

1. Any runtime service with native dependencies uses a glibc-based JRE image. The default is `eclipse-temurin:21-jre-jammy`; an equivalent Debian/Ubuntu image is acceptable.
2. Alpine is permitted only for services with zero native dependencies. A service that uses gRPC/Netty native transport, `netty-tcnative`, epoll, JNI/JNA, or native JDBC drivers is not eligible.
3. Build stages that execute `protoc`/`protoc-gen-grpc-java` must use glibc or install `libc6-compat` in the Alpine build stage. This is separate from the runtime rule and does not make an Alpine runtime safe.
4. Current gRPC-bearing runtime images are `synflux`, `synquest`, `gateway`, `extraction-gateway`, and the externally built `gpu-gateway`. `syntology` and `relix` use HTTP for their current cross-service calls and are not in this set; `syntology-admin` is a static nginx image.
5. `scripts/ci/check-base-images.sh` is the canonical static check. It is wired into `./gradlew buildAll` so a new Alpine runtime for a listed gRPC service fails CI.
6. New or changed Dockerfiles must be reviewed against the module's dependency profile. When a new native service is added, add its runtime Dockerfile to the CI list in the same change.
7. gRPC services log native epoll availability at startup. NIO fallback is acceptable only when explicit and observable; it is not a silent substitute for a correct base image.

Consequences:
- **Correctness:** removes the glibc/musl `UnsatisfiedLinkError`, `SIGSEGV`, and missing-epoll failure class for gRPC services.
- **Size:** each affected image grows by roughly 50 MB; acceptable versus intermittent runtime failure during activation.
- **Enforcement:** the static check is fast and deterministic; the live gRPC call remains covered by service tests and #589 activation evidence.
- **Rejected:** `gcompat` + `LD_PRELOAD` as a default. It is fragile, depends on loader ordering, and does not cover arbitrary JNI libraries.
- **Rejected:** forcing `-Dio.netty.transport.noNative=true` everywhere. NIO is acceptable for demo scale, but it is a fallback, not a substitute for a glibc runtime where native transport is expected.

## Context

PR #601 proved the RAKG pipeline end-to-end on local Compose. The runtime images were copied forward from existing Alpine Dockerfiles, while `synflux` and `synquest` pull in `grpc-netty-shaded` through `extraction-client` and `gpu-client`; `gateway` uses it directly. The native transport path is not exercised by the local HTTP mock extraction path, so the failure can remain latent until K8s activation sends a real gRPC call.

The build-time and runtime concerns are distinct: this repository generates gRPC stubs on the host or in Debian JDK build stages, so `libc6-compat` is not the runtime fix. The durable rule is dependency-based: native dependencies require glibc, regardless of whether the next addition is gRPC, Netty, JNI, JNA, or a native JDBC driver.
