import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spotless)
}

group = "app.soundcheck"
version = "0.1.0-SNAPSHOT"
description = "Soundcheck backend — Spring Boot modular monolith (roles api, scheduler, worker)"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// ── jOOQ code generation from the real schema ────────────────────────────────────────────────
// A small program in the `codegen` source set starts a PostGIS container (Testcontainers), applies the
// Flyway migrations and runs the jOOQ generator. Generated sources are never committed.
val jooqOutputDir = layout.buildDirectory.dir("generated-src/jooq/main")
val jooqPackage = "app.soundcheck.shared.jooq"

sourceSets {
    create("codegen")
    main {
        java.srcDir(jooqOutputDir)
    }
}

val codegenImplementation: Configuration by configurations.getting

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform(libs.modulith.bom))
    implementation(platform(libs.aws.bom))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.tracing.otel)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.micrometer.registry.otlp)
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    implementation(libs.modulith.starter.core)
    implementation(libs.modulith.starter.jdbc)
    implementation(libs.modulith.actuator)

    implementation(libs.aws.s3)
    implementation(libs.aws.sqs)
    implementation(libs.aws.url.connection.client)

    implementation(libs.spring.retry) // @Retryable on the external API clients
    implementation(libs.spring.boot.starter.aop)
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.jdbc.template)
    implementation(libs.uuid.generator)
    implementation(libs.springdoc.webmvc.ui)

    codegenImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    codegenImplementation(libs.jooq.codegen)
    codegenImplementation(libs.jooq.meta)
    codegenImplementation(libs.flyway.core)
    codegenImplementation(libs.flyway.postgresql)
    codegenImplementation(libs.postgresql)
    codegenImplementation(libs.testcontainers.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.modulith.starter.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.jqwik)
    testImplementation(libs.wiremock)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val generateJooq by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Applies Flyway migrations to a throwaway PostGIS container and generates jOOQ sources"
    classpath = sourceSets["codegen"].runtimeClasspath
    mainClass = "app.soundcheck.codegen.JooqFromFlyway"
    val migrations = layout.projectDirectory.dir("src/main/resources/db/migration")
    inputs.dir(migrations)
    inputs.files(sourceSets["codegen"].runtimeClasspath)
    outputs.dir(jooqOutputDir)
    args(migrations.asFile.absolutePath, jooqOutputDir.get().asFile.absolutePath, jooqPackage)
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(generateJooq)
}

springBoot {
    buildInfo()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    // `make openapi` passes -PopenapiWrite=true so OpenApiSnapshotIT rewrites contracts/openapi.json.
    systemProperty("soundcheck.openapi.write", providers.gradleProperty("openapiWrite").getOrElse("false"))
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Spring Modulith's Documenter writes module docs here (ModularityTests).
    systemProperty(
        "spring.modulith.docs.output",
        layout.buildDirectory
            .dir("spring-modulith-docs")
            .get()
            .asFile.absolutePath,
    )
}

spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat(
            libs.versions.palantir.java.format
                .get(),
        )
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

// Convenience: `./gradlew bootRun --args='--spring.profiles.active=local'` is wrapped by `make api` / `make worker`.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("-XX:+UseZGC")
}
