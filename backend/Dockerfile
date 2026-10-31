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
# Memoria: el plan de Render tiene 512 MB para TODO el proceso, no solo el heap. Con 75% de heap (384 MB) más
# ~200 MB fuera del heap (clases de Spring/Hibernate, código compilado, hilos) se pasaba del límite al arrancar.
# Heap 45% (~230 MB): el arranque en frío del analizador de consultas pide ~150 MB vivos un momento (con 40% no
# arranca) y luego quedan ~90 MB. Medido con -XX:MaxRAM=512m: pico total ~450 MB. Metaspace y caché de código
# acotados, GC serial (1 CPU) y JIT de nivel 1 (menos memoria y arranque más rápido en CPU compartida).
# Menos hilos y conexiones también ahorran memoria; ambas se pueden subir con variables en Render.
ENV SPRING_PROFILES_ACTIVE=prod PORT=8080 TZ=America/Bogota SERVER_THREADS_MAX=60 DB_POOL_MAX_SIZE=8 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=45 -XX:MaxMetaspaceSize=170m -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=32m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 CMD curl -fs http://localhost:${PORT}/api/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
