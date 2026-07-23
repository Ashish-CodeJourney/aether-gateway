# Build context is the repository root (see docker-compose.yml).

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace
COPY aether-gateway ./aether-gateway
WORKDIR /workspace/aether-gateway
RUN ./gradlew :mock-provider:bootJar --no-daemon -x test

FROM build AS layers
WORKDIR /workspace/aether-gateway
RUN java -Djarmode=tools -jar mock-provider/build/libs/mock-provider.jar extract --layers --destination /layers

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S aether && adduser -S aether -G aether
WORKDIR /app
COPY --from=layers /layers/dependencies/ ./
COPY --from=layers /layers/spring-boot-loader/ ./
COPY --from=layers /layers/snapshot-dependencies/ ./
COPY --from=layers /layers/application/ ./
USER aether
EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=5 \
    CMD wget -q -O- http://localhost:8081/healthz || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
