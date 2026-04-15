plugins {
    id("java-library")
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    // Testing dependencies
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
}

// Disable tests and benchmarks to avoid cross-compilation execution errors
tasks.withType<Test> {
    enabled = false
}
