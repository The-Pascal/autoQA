plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.0"
    id("org.jetbrains.intellij") version "1.13.1"
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
    val retrofitVersion = "2.11.0"
    implementation("com.squareup.retrofit2:retrofit:${retrofitVersion}")
    implementation("com.squareup.retrofit2:converter-gson:${retrofitVersion}")

    implementation("com.google.genai:google-genai:0.1.0")
}

tasks {
    runIde {
        ideDir.set(file("C:/Program Files/Android/Android Studio"))
    }
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "11"
        targetCompatibility = "11"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "11"
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
