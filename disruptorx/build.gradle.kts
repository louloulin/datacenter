plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("me.champeau.jmh") version "0.7.1"
}

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    
    // LMAX Disruptor
    implementation("com.lmax:disruptor:3.4.4")
    
    // Netty for networking
    implementation("io.netty:netty-all:4.1.86.Final")
    
    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")
    
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.5")
    implementation("ch.qos.logback:logback-classic:1.4.6")
    
    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.9.2")
    testImplementation("io.mockk:mockk:1.13.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.4")
    
    // Metrics & Monitoring
    implementation("io.micrometer:micrometer-core:1.10.5")
    implementation("org.hdrhistogram:HdrHistogram:2.1.12")
    testImplementation("org.hdrhistogram:HdrHistogram:2.1.12")
    
    // JMH Benchmarking
    jmh("org.openjdk.jmh:jmh-core:1.36")
    jmh("org.openjdk.jmh:jmh-generator-annprocess:1.36")
    testImplementation("org.openjdk.jmh:jmh-core:1.36")
    testImplementation("org.openjdk.jmh:jmh-generator-annprocess:1.36")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

// Configure JMH plugin
jmh {
    warmupIterations.set(5)
    iterations.set(5)
    fork.set(1)
} 