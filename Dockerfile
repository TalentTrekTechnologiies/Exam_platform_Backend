# ── Build stage ──────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Cache dependencies separately from source, so code changes don't re-download.
COPY pom.xml .
RUN mvn -q -o dependency:go-offline || mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q clean package -DskipTests

# ── Runtime stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre
WORKDIR /app

# Run as a non-root user — nothing here needs privilege.
RUN useradd --system --uid 10001 exam
COPY --from=build /app/target/Exam_System-0.0.1-SNAPSHOT.jar app.jar

# Uploads live on a shared volume mounted here, so every instance sees the same
# files and nginx can serve them without going through the app.
RUN mkdir -p /app/uploads && chown -R exam:exam /app
USER exam

EXPOSE 8080

# Container-aware heap sizing; the rest of the JVM flags stay default.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"

# Fail fast if the app can't answer — the load balancer uses this.
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/health >/dev/null 2>&1 || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
