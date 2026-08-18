FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY java/synflux-router/build/libs/synflux-router.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "app.jar"]
