# Ecomerxio POS: solo la API (el frontend se publica en Vercel desde frontend/).
#   docker build -t ecomerxio-pos-api backend/
# 1) Compilación
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package && cp target/tiendaposs-backend-*.jar /app/app.jar

# 2) Ejecución
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --home /app app && apt-get update \
    && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/app.jar ./app.jar
USER app
ENV SPRING_PROFILES_ACTIVE=prod PORT=8080 TZ=America/Bogota \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 CMD curl -fs http://localhost:${PORT}/api/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
