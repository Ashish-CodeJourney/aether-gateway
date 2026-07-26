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

# Phase 08 (M5) fix: `extract --layers` on Spring Boot 4.1 produces an
# empty spring-boot-loader/ directory (a real, reproduced upstream
# packaging bug - the layers.idx correctly maps "spring-boot-loader" to
# the jar's bare "org/" prefix, but the tool's layered extraction does
# not actually populate it, so the resulting image's Main-Class,
# JarLauncher, is missing and the container fails immediately with
# "Could not find or load main class"). Plain (non-layered) `extract`
# does not hit this bug: it produces a lib/ directory of dependency
# jars plus a thin application jar (loader classes included), which
# still gets the same PRD section 17.1 caching benefit - lib/ rarely
# changes and is copied in its own layer, so app-only rebuilds stay
# fast - via a different, correctly-working directory split.
FROM build AS extracted
WORKDIR /workspace/aether-gateway
RUN java -Djarmode=tools -jar gateway-proxy/build/libs/gateway-proxy.jar extract --destination /extracted

# Phase 07/08 (M4/M5) fix: NOT eclipse-temurin:25-jre-alpine here,
# deliberately, unlike mock-provider's Dockerfile. The ONNX Runtime
# native library (spring-ai-transformers' embedding model, F4.3) is a
# glibc binary using glibc's "fortified" libc symbols (e.g.
# __vsnprintf_chk); Alpine's musl libc has no glibc at all, and even
# gcompat (Alpine's glibc-compatibility shim) does not cover the
# fortified symbol set, so the container died with "Error relocating
# libonnxruntime.so: __vsnprintf_chk: symbol not found" even after
# installing every musl-compatibility package that got it past the
# earlier missing-library errors. Rather than keep chasing individual
# missing glibc symbols on a fundamentally incompatible libc, this uses
# eclipse-temurin's real-glibc (Debian-based) image, which the ONNX
# native library works with unmodified. Larger image than Alpine, but
# correctness over image size. mock-provider has no embedding
# dependency and stays on Alpine.
FROM eclipse-temurin:25-jre
# Trivy (CI) catches real, fixed-upstream HIGH/CRITICAL CVEs in the base
# image's own OS packages that the temurin image tag hasn't picked up
# yet - upgrading at build time pulls patched versions in without
# waiting for a new base image tag (same fix as mock-provider's
# Dockerfile, apt's equivalent of apk upgrade).
RUN apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd -r aether && useradd -r -g aether aether
WORKDIR /app
COPY --from=extracted /extracted/lib/ ./lib/
COPY --from=extracted /extracted/gateway-proxy.jar ./gateway-proxy.jar
USER aether
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=5 \
    CMD wget -q -O- http://localhost:8080/healthz || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "gateway-proxy.jar"]
