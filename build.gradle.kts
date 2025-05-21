import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.8.20"
    kotlin("plugin.serialization") version "1.8.20"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    application
}

group = "com.hftdc"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    // Chronicle Wire repository temporarily removed
    //maven { url = uri("https://repo.cash/repository/maven-releases") }
}

val akkaVersion = "2.7.0"
val arrowVersion = "1.2.0"
val disruptorVersion = "3.4.4"
//val chronicleWireVersion = "2.23.8"
val coroutinesVersion = "1.7.2"
val kotlinSerializationVersion = "1.5.0"
val postgresVersion = "42.5.4"
val logbackVersion = "1.4.7"
val prometheusVersion = "0.16.0"
val junitVersion = "5.9.3"
val mockkVersion = "1.13.5"
val ktorVersion = "2.3.0"

dependencies {
    // Kotlin
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinSerializationVersion")
    
    // Ktor - REST API
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    
    // Disruptor
    implementation("com.lmax:disruptor:$disruptorVersion")
    
    // Akka
    implementation("com.typesafe.akka:akka-actor-typed_2.13:$akkaVersion")
    implementation("com.typesafe.akka:akka-cluster-typed_2.13:$akkaVersion")
    implementation("com.typesafe.akka:akka-serialization-jackson_2.13:$akkaVersion")
    implementation("com.typesafe.akka:akka-stream_2.13:$akkaVersion")
    implementation("com.typesafe.akka:akka-persistence-typed_2.13:$akkaVersion")
    
    // Arrow - Functional Programming
    implementation("io.arrow-kt:arrow-core:$arrowVersion")
    implementation("io.arrow-kt:arrow-fx-coroutines:$arrowVersion")
    
    // Chronicle Wire - temporarily removed due to repository issues
    //implementation("net.openhft:chronicle-wire:$chronicleWireVersion")
    
    // Database
    implementation("org.postgresql:postgresql:$postgresVersion")
    implementation("com.zaxxer:HikariCP:5.0.1")
    
    // Logging
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    
    // Monitoring
    implementation("io.prometheus:simpleclient:$prometheusVersion")
    implementation("io.prometheus:simpleclient_hotspot:$prometheusVersion")
    implementation("io.prometheus:simpleclient_httpserver:$prometheusVersion")
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    testImplementation("io.mockk:mockk:$mockkVersion")
    testImplementation("com.typesafe.akka:akka-testkit_2.13:$akkaVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf("-Xjsr305=strict", "-Xopt-in=kotlin.RequiresOptIn")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

application {
    mainClass.set("com.hftdc.ApplicationKt")
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "com.hftdc.ApplicationKt"
    }
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveBaseName.set("hftdc")
    archiveClassifier.set("")
    archiveVersion.set("")
} 