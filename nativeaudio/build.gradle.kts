// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.cisco.nativeaudio"
    compileSdk = 36

    defaultConfig {
        minSdk = 30

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags("")
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DBUILD_TESTING=OFF",
                    "-DBUILD_EXAMPLES=OFF",
                    "-DBUILD_BENCHMARKS=OFF"
                )
            }
        }
    }

    buildFeatures {
        prefab = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Disable tests and benchmarks to avoid cross-compilation execution errors
tasks.withType<Test> {
    enabled = false
}

afterEvaluate {
    tasks.findByName("connectedAndroidTest")?.enabled = false
    tasks.findByName("connectedDebugAndroidTest")?.enabled = false
}

// ---------------------------------------------------------------------------
// Host unit tests for the native VAD logic (VadGate, see src/main/cpp/vad_gate.h).
//
// The Android externalNativeBuild cross-compiles for the device and can't run the
// tests, and Gradle's JVM `Test` tasks are disabled above. So these are wired as
// plain Exec tasks that compile and run the test on the build machine with the host
// C++ compiler (override with -PcxxCompiler=... or the CXX env var). CMake is not
// required (src/test/cpp/CMakeLists.txt remains available for IDE/manual use).
// ---------------------------------------------------------------------------
val hostCxx = (project.findProperty("cxxCompiler") as String?)
    ?: System.getenv("CXX")
    ?: "c++"

val vadTestOutputDir = layout.buildDirectory.dir("vad-host-tests")
val vadTestBinary = vadTestOutputDir.map { it.file("vad_gate_test") }

val compileVadHostTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Compiles the native VAD (VadGate) host unit tests."

    val testSource = file("src/test/cpp/vad_gate_test.cpp")
    inputs.file(testSource)
    inputs.file("src/main/cpp/vad_gate.h")
    outputs.file(vadTestBinary)

    doFirst { vadTestOutputDir.get().asFile.mkdirs() }
    commandLine(
        hostCxx, "-std=c++17", "-Wall", "-Wextra",
        "-I", file("src/main/cpp").absolutePath,
        testSource.absolutePath,
        "-o", vadTestBinary.get().asFile.absolutePath
    )
}

val vadHostTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the native VAD (VadGate) host unit tests."
    dependsOn(compileVadHostTest)
    commandLine(vadTestBinary.get().asFile.absolutePath)
}

// Run the native VAD tests as part of `check` (and therefore `build`).
tasks.named("check") {
    dependsOn(vadHostTest)
}