FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache wget \
    && addgroup -S synanton -g 2000 \
    && adduser -S alice -G synanton -u 1001 \
    && mkdir -p /var/lib/syntology/tdb2 /data/ontologies \
    && chown -R alice:synanton /var/lib/syntology /data/ontologies

USER alice
WORKDIR /app
COPY java/syntology/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
