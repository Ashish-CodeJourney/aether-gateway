// Load generator and eval harness: Phase 09 (M6).

// Experiment 5 (concurrency ceiling): a standalone, benchmark-only MVC
// plus virtual-threads SSE relay, kept in its own source set so its
// Tomcat/Spring MVC dependencies never leak onto gateway-bench's main
// or test classpaths (which stay WebFlux-free, matching every other
// module). See MvcStreamingComparisonApplication's javadoc.
// The Java plugin's default convention for a source set named
// "mvcComparison" is already src/mvcComparison/{java,resources} - no
// explicit srcDir needed (adding it again duplicates the same
// directory and breaks the resources Copy task).
val mvcComparison = sourceSets.create("mvcComparison")

tasks.register<JavaExec>("runMvcComparisonApp") {
    description = "Runs the experiment 5 MVC+virtual-threads SSE relay comparison app."
    group = "benchmark"
    classpath = mvcComparison.runtimeClasspath
    mainClass.set("com.aether.gateway.bench.mvccomparison.MvcStreamingComparisonApplication")
}

dependencies {
    "mvcComparisonImplementation"("org.springframework.boot:spring-boot-starter-web")


    api(project(":gateway-core"))
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Experiment runners only (real embedding model against the real corpus).
    integrationTestImplementation(project(":gateway-cache"))
    // Experiment 3 (embedding model comparison) constructs a second,
    // non-default TransformersEmbeddingModel directly - EmbeddingGenerator
    // (gateway-cache) only ever wires up the product default (MiniLM-L6).
    integrationTestImplementation(libs.spring.ai.transformers)
    // Experiment 4 (HNSW parameter tuning) needs direct pgvector index
    // control (m, ef_search) against a real Testcontainers Postgres,
    // via its own purpose-built table - not PgVectorCacheStore's schema.
    integrationTestImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    integrationTestRuntimeOnly("org.postgresql:postgresql")
    integrationTestImplementation(libs.testcontainers)
    integrationTestImplementation(libs.testcontainers.junit.jupiter)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
