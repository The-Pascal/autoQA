plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
}

group = "com.brahamchari"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.0")
    implementation("io.modelcontextprotocol:kotlin-sdk:0.3.0")
    implementation("org.slf4j:slf4j-nop:2.0.9")
}

tasks.test {
    useJUnitPlatform()
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }
}