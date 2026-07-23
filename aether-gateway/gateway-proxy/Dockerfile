# Build context is the repository root (see docker-compose.yml), so both
# aether-gateway/ and db/ (Flyway's source of truth, copied onto the
# classpath at build time, see gateway-proxy/build.gradle.kts) are
# visible.

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace
COPY db ./db
COPY aether-gateway ./aether-gateway
WORKDIR /workspace/aether-gateway
RUN ./gradlew :gateway-proxy:bootJar --no-daemon -x test

FROM build AS layers
WORKDIR /workspace/aether-gateway
RUN java -Djarmode=tools -jar gateway-proxy/build/libs/gateway-proxy.jar extract --layers --destination /layers

# Dependencies layer before application layer, per PRD section 17.1:
# rebuilds that only touch application code stay fast because the
# dependency layers above them are unchanged and Docker caches them.
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S aether && adduser -S aether -G aether
WORKDIR /app
COPY --from=layers /layers/dependencies/ ./
COPY --from=layers /layers/spring-boot-loader/ ./
COPY --from=layers /layers/snapshot-dependencies/ ./
COPY --from=layers /layers/application/ ./
USER aether
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=5 \
    CMD wget -q -O- http://localhost:8080/healthz || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
