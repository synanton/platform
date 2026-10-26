FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S synanton && adduser -S synanton -G synanton
USER synanton
WORKDIR /app
COPY java/security/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
