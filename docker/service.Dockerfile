# One image recipe for every service; the jar is built on the host by ./gradlew bootJar.
FROM eclipse-temurin:21-jre
ARG JAR
WORKDIR /app
COPY ${JAR} app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
