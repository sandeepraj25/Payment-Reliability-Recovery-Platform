# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- run stage: JRE only, non-root user ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 payguard
WORKDIR /app
COPY --from=build /src/target/payguard-backend-*.jar app.jar
USER payguard
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
