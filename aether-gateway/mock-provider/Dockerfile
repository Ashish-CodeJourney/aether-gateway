# Build context is the repository root (see docker-compose.yml).

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace
COPY aether-gateway ./aether-gateway
WORKDIR /workspace/aether-gateway
RUN ./gradlew :mock-provider:bootJar --no-daemon -x test

# Phase 08 (M5) fix: see gateway-proxy/Dockerfile's comment - `extract
# --layers` produces an empty spring-boot-loader/ directory on Spring
# Boot 4.1 (reproduced, real upstream packaging bug), so the container's
# Main-Class is missing and it fails immediately. Plain `extract`
# avoids the bug and keeps the same dependency/application layer-caching
# split via lib/ + a thin application jar instead.
FROM build AS extracted
WORKDIR /workspace/aether-gateway
RUN java -Djarmode=tools -jar mock-provider/build/libs/mock-provider.jar extract --destination /extracted

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S aether && adduser -S aether -G aether
WORKDIR /app
COPY --from=extracted /extracted/lib/ ./lib/
COPY --from=extracted /extracted/mock-provider.jar ./mock-provider.jar
USER aether
EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=5 \
    CMD wget -q -O- http://localhost:8081/healthz || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "mock-provider.jar"]
