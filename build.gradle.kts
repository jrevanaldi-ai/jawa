// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    `java-library`
    application
    id("com.google.protobuf") version "0.9.4"
}

application {
    mainClass = "id.jawa.Main"
}

tasks.named<JavaExec>("run") {
    val sf = providers.gradleProperty("sessionFile").orNull
    if (sf != null) systemProperty("jawa.session", sf)
    standardInput = System.`in`
}

group = "id.jawa"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
}

configurations.all {
    resolutionStrategy.eachDependency {
        // signal-protocol-java 2.8.1 ships protobuf-javalite:3.10.0 which is incompatible
        // with newer protobuf-java schema parsing. Pin all protobuf artifacts to a single
        // matched version so libsignal's lite messages parse correctly.
        if (requested.group == "com.google.protobuf") {
            useVersion("3.10.0")
        }
    }
}

dependencies {
    // Crypto primitives — Curve25519, AES-GCM, HKDF, SHA256, HMAC
    api("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Signal Protocol (X3DH, Double Ratchet, Sender Keys)
    // GPL-3.0 — license cascades to JaWa.
    // TODO: evaluate org.signal:libsignal-client (current, native deps) vs signal-protocol-java (archived, pure-Java).
    // Starting with the archived pure-Java port for portability; Signal Protocol algorithms are stable.
    api("org.whispersystems:signal-protocol-java:2.8.1")

    // Protobuf runtime
    api("com.google.protobuf:protobuf-java:3.10.0")

    // WebSocket client
    implementation("com.neovisionaries:nv-websocket-client:2.14")

    // QR code rendering for the demo runner
    implementation("com.google.zxing:core:3.5.3")

    // Logging facade (binding pluggable; logback as runtime default for the demo)
    api("org.slf4j:slf4j-api:2.0.13")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.6")

    // ---- test ----
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.26.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.6")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.10.0"
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "JaWa",
            "Implementation-Version" to project.version,
            "Automatic-Module-Name" to "id.jawa"
        )
    }
}
