import com.google.protobuf.gradle.id

plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("com.google.protobuf") version "0.9.4"
    `java-library`
    `maven-publish`
}

group = "io.raskell.zentinel"
version = "0.3.0"

val grpcVersion = "1.68.1"
val protobufVersion = "3.25.5"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // gRPC transport (zentinel.agent.v2.AgentServiceV2)
    api("io.grpc:grpc-protobuf:$grpcVersion")
    api("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    api("com.google.protobuf:protobuf-java:$protobufVersion")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("io.mockk:mockk:1.13.9")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// The examples in examples/ are compiled (so they cannot rot) and installed
// with their runtime classpath by `installExamples`:
//   java -cp 'build/examples/lib/*' io.raskell.zentinel.agent.examples.HelloAgentKt /tmp/hello.sock
val examples: SourceSet by sourceSets.creating {
    kotlin.srcDir("examples")
    compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

val examplesJar by tasks.registering(Jar::class) {
    archiveBaseName.set("zentinel-agent-kotlin-sdk-examples")
    from(examples.output)
}

val installExamples by tasks.registering(Sync::class) {
    description = "Installs the examples and their runtime classpath into build/examples/lib."
    group = "distribution"
    from(examplesJar)
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("examples/lib"))
}

tasks.test {
    useJUnitPlatform()
    // The integration test runs the examples behind a real zentinel binary
    // when ZENTINEL_BIN is set, and skips itself otherwise.
    dependsOn(installExamples)
    systemProperty("zentinel.examples.lib", layout.buildDirectory.dir("examples/lib").get().asFile.absolutePath)
    systemProperty("zentinel.repo.root", rootDir.absolutePath)
}

tasks.named("assemble") {
    dependsOn(installExamples)
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("Zentinel Agent Kotlin SDK")
                description.set("SDK for building Zentinel proxy agents in Kotlin")
                url.set("https://github.com/zentinelproxy/zentinel-agent-kotlin-sdk")

                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }

                scm {
                    url.set("https://github.com/zentinelproxy/zentinel-agent-kotlin-sdk")
                    connection.set("scm:git:git://github.com/zentinelproxy/zentinel-agent-kotlin-sdk.git")
                }
            }
        }
    }
}
