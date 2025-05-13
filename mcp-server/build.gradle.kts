plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.0"
    kotlin("plugin.serialization") version "1.9.0"
}

group = "com.brahamchari"
version = "1.5-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {

    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.0")

    implementation("io.modelcontextprotocol:kotlin-sdk:0.4.0") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.slf4j", module = "slf4j-api")
        exclude(group = "org.slf4j", module = "slf4j-jul") // Exclude specific bindings too
    }

    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3") { // Use your kotlin-logging version
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
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