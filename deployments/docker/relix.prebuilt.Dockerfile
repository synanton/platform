FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache wget \
    && addgroup -S synanton -g 2000 \
    && adduser -S alice -G synanton -u 1001

USER alice
WORKDIR /app
COPY java/relix/build/libs/relix*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
