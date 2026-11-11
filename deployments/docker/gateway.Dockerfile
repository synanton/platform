# syntax=docker/dockerfile:1
# glibc build image: protoc / protoc-gen-grpc-java (gpu-contract) are glibc binaries
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradle ./gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties* ./
COPY java/synanton-llm-client ./java/synanton-llm-client
# synanton.gpu.v1 contract + shared GPU-plane client (gpu-plane profile / GpuExecutionClient)
COPY java/gpu-contract ./java/gpu-contract
COPY java/gpu-client ./java/gpu-client
COPY java/gateway ./java/gateway
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :java:gateway:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system synanton \
    && useradd --system --gid synanton --create-home synanton
USER synanton
WORKDIR /app
COPY --from=build /workspace/java/gateway/build/libs/gateway*.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "app.jar"]
