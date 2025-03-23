plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.0"
    kotlin("plugin.serialization") version "1.9.0"
    application
}

application {
    mainClass.set("com.brahamchari.MainKt")
}

group = "com.brahamchari"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    implementation(kotlin("stdlib-jdk8"))
    implementation("io.modelcontextprotocol:kotlin-sdk:0.3.0")
    implementation("org.slf4j:slf4j-nop:2.0.9")
}

tasks.test {
    useJUnitPlatform()
}