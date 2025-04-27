plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.0"
    id("org.jetbrains.intellij") version "1.13.1"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.brahamchari"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2023.1.1")
    type.set("IC") // Target IDE Platform

    plugins.set(listOf("Kotlin", "org.jetbrains.android"))
}

dependencies {
    val mcpVersion = "0.4.0"
    val slf4jVersion = "2.0.9"
    val anthropicVersion = "0.8.0"

    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.0")

    implementation("io.modelcontextprotocol:kotlin-sdk:$mcpVersion") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.slf4j", module = "slf4j-api")
        exclude(group = "org.slf4j", module = "slf4j-jul") // Exclude specific bindings too
    }


    val retrofitVersion = "2.11.0"
    implementation("com.squareup.retrofit2:retrofit:${retrofitVersion}")
    implementation("com.squareup.retrofit2:converter-gson:${retrofitVersion}")

    implementation("com.google.genai:google-genai:0.1.0") {
        exclude(group = "com.google.guava", module = "guava") // <-- ADD THIS EXCLUSION
    }
    implementation(project(":mcp-server")) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    }

    implementation("com.anthropic:anthropic-java:1.1.0")
}

tasks {
    runIde {
        ideDir.set(file("C:/Program Files/Android/Android Studio"))
    }
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("222") // Minimum supported build (Android Studio Flamingo)
        untilBuild.set("999.*")   // Allow all future versions
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
