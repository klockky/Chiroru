plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.chiron.chiroru"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        // Keep hardware preflight tests separate from the user's running loader.
        applicationId = if (providers.gradleProperty("chiroru.preflight").orNull == "true")
            "dev.chiron.chiroru.preflight" else "dev.chiron.chiroru"
        minSdk = 30
        // Preserve the original loader's Android 11 behavior during this migration.
        // Raising this requires a separate review of foreground-service/boot rules.
        targetSdk = 30
        versionCode = 51
        versionName = "0.8.1"
        // Factory builds ship a 32-bit-only userspace.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=none" } }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging { jniLibs.useLegacyPackaging = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            // Local station builds must update the existing debug-signed app
            // without uninstalling it or losing its settings and boot report.
            signingConfig = if (providers.gradleProperty("chiroru.unsigned").orNull == "true")
                null else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint {
        // targetSdk intentionally matches the original, device-specific application.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.ui:ui:1.10.5")
    implementation("androidx.compose.foundation:foundation:1.10.5")
    implementation("androidx.compose.animation:animation:1.10.5")
    implementation("androidx.compose.ui:ui-tooling-preview:1.10.5")
    // Expressive APIs, pinned to the version verified with compileSdk 36 / AGP 8.
    implementation("androidx.compose.material3:material3:1.5.0-alpha10")
    debugImplementation("androidx.compose.ui:ui-tooling:1.10.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.10.5")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.10.5")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.10.5")
}

val verifyPteWindowCleanup by tasks.registering(Exec::class) {
    workingDir(projectDir)
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine("python3", "-m", "unittest", "scripts/test_pte_window_cleanup.py")
}

val testNativeProcessWait by tasks.registering(Exec::class) {
    description = "Tests subprocess deadlines on the host without running native root operations."
    group = "verification"
    workingDir(projectDir)
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine("python3", "-m", "unittest", "scripts/test_process_wait.py")
}

tasks.named("check").configure {
    dependsOn(testNativeProcessWait, "testFirmwareCatalog", "testConsoleRunner", "testProcessConsole", "testAioCleanup", "testDeviceGuard", "testPolicyHostCompat", "testKptrRestore")
}

tasks.register<Exec>("testKptrRestore") {
    description = "Checks that KernelSU loading restores kptr_restrict."
    group = "verification"
    workingDir(projectDir)
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine("python3", "-m", "unittest", "scripts/test_kptr_restore.py")
}

tasks.register<Exec>("testPolicyHostCompat") {
    description = "Compiles the host policy shims as 32-bit and checks ebitmap bit helpers."
    group = "verification"
    workingDir(projectDir)
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine("python3", "-m", "unittest", "scripts/test_policy_host_compat.py")
}

tasks.register<Exec>("testConsoleRunner") {
    group = "verification"
    workingDir(projectDir)
    commandLine("python3", "-m", "unittest", "scripts/test_console_runner.py")
}

tasks.register<Exec>("testProcessConsole") {
    group = "verification"
    workingDir(rootProject.projectDir.resolve("../.."))
    commandLine("python3", "root-console/test_server.py")
}

tasks.named("preBuild").configure {
    dependsOn(verifyPteWindowCleanup, "verifyFirmwareCatalog", "testAioCleanup", "testDeviceGuard", "testNativeFirmwareProfiles", "testKptrRestore")
}

tasks.register<Exec>("testNativeFirmwareProfiles") {
    group = "verification"
    workingDir(projectDir)
    commandLine("python3", "-m", "unittest", "scripts/test_native_firmware_profiles.py")
}

tasks.register<Exec>("testAioCleanup") {
    group = "verification"
    workingDir(projectDir)
    commandLine("python3", "-m", "unittest", "scripts/test_aio_cleanup.py")
}

tasks.register<Exec>("testDeviceGuard") {
    group = "verification"
    workingDir(projectDir)
    commandLine("python3", "-m", "unittest", "scripts/test_device_guard.py")
}

tasks.register<Exec>("verifyFirmwareCatalog") {
    description = "Verifies firmware identities, native backend IDs and bundled payload hashes."
    workingDir(projectDir)
    commandLine("python3", "scripts/verify_firmware_catalog.py")
}

tasks.register<Exec>("testFirmwareCatalog") {
    description = "Tests root-only catalogs and independent KernelSU payload validation."
    group = "verification"
    workingDir(projectDir)
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine("python3", "-m", "unittest", "scripts/test_firmware_catalog.py")
}
