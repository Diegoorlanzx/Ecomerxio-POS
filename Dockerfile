# TiendaPOSS: imagen única con la API y la app web (PWA).
#   docker build -t tiendaposs .
# 1) App web
FROM node:22-alpine AS web
RUN apk add --no-cache python3 py3-pip && pip3 install --no-cache-dir --break-system-packages fonttools brotli
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# 2) Servidor (incluye la app web dentro del .jar)
FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /app/backend
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=web /app/frontend/dist /app/frontend/dist
RUN mvn -q -B -Pprod -DskipTests package && cp target/tiendaposs-backend-*.jar /app/tiendaposs.jar

# 3) Ejecución
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --home /app app && apt-get update \
    && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=api /app/tiendaposs.jar ./tiendaposs.jar
USER app
ENV SPRING_PROFILES_ACTIVE=prod PORT=8080 TZ=America/Bogota \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 CMD curl -fs http://localhost:8080/api/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/tiendaposs.jar"]
