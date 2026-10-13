plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.maxmfonseca.pontinho"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.maxmfonseca.pontinho"
        minSdk = 26
        //noinspection OldTargetApi -- API 36 is the approved release baseline.
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets.named("test") {
        resources.directories.add("../ml/card-recognition/tests/fixtures/android-release")
    }
    sourceSets.named("androidTest") { assets.directories.add("$projectDir/schemas") }

    lint {
        // The task deliberately pins AGP 9.4.0 and Gradle 9.6.0 as one tested baseline.
        disable += "AndroidGradlePluginVersion"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

tasks.register("verifyOfflineScannerDependencies") {
    dependsOn("assembleDebug", "assembleRelease")
    inputs.files(configurations.named("releaseRuntimeClasspath"))
    val sdkDirectory = androidComponents.sdkComponents.sdkDirectory
    val buildToolsVersion = android.buildToolsVersion
    val debugApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val releaseApk = layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk")
    inputs.files(debugApk, releaseApk)
    doLast {
        val forbiddenArtifactPrefixes = setOf(
            "ai-delivery-",
            "asset-delivery-",
            "transport-api-",
            "transport-runtime-",
            "transport-backend-cct-",
            "tasks-vision-",
            "tasks-core-",
            "firebase-analytics-",
            "firebase-crashlytics-",
            "firebase-encoders-json-",
            "firebase-encoders-proto-",
            "play-services-measurement-",
            "play-services-basement-",
            "play-services-tasks-",
            "grpc-android-",
            "grpc-okhttp-",
            "okhttp-",
            "retrofit-",
            "volley-",
            "work-runtime-",
            "httpclient-",
        )
        val artifacts = inputs.files.map { it.name }
        val liteRtArtifacts = artifacts.filter { it.startsWith("litert-") }.toSet()
        check(liteRtArtifacts == setOf("litert-2.3.0.aar", "litert-api-2.3.0.aar")) {
            "Offline scanner LiteRT graph must be exactly the bundled runtime and API: ${liteRtArtifacts.sorted()}"
        }
        val offenders = artifacts
            .filter { name -> forbiddenArtifactPrefixes.any(name::startsWith) }
            .sorted()
        check(offenders.isEmpty()) {
            "Offline scanner contains networking/telemetry artifacts: ${offenders.joinToString()}"
        }

        val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val aapt = sdkDirectory.get().file("build-tools/$buildToolsVersion/aapt$suffix").asFile
        val forbiddenManifestTokens = setOf(
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.INTERNET",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.WAKE_LOCK",
            "androidx.work",
            "com.google.android.play",
            "com.google.android.gms",
        )
        listOf(debugApk.get().asFile, releaseApk.get().asFile).forEach { apk ->
            val process = ProcessBuilder(
                aapt.absolutePath,
                "dump",
                "xmltree",
                apk.absolutePath,
                "AndroidManifest.xml",
            ).redirectErrorStream(true).start()
            val manifest = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { manifest }
            val manifestOffenders = forbiddenManifestTokens.filter(manifest::contains)
            check(manifestOffenders.isEmpty()) {
                "Offline APK ${apk.name} contains delivery/network components: ${manifestOffenders.joinToString()}"
            }
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Inspect the packaged release artifact, including features implied by CAMERA permission.
tasks.register("verifyManualOnlyReleaseApk") {
    dependsOn("assembleRelease")
    val sdkDirectory = androidComponents.sdkComponents.sdkDirectory
    val buildToolsVersion = android.buildToolsVersion
    val apk = layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk")
    inputs.file(apk)
    doLast {
        val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val aapt = sdkDirectory.get().file("build-tools/$buildToolsVersion/aapt$suffix").asFile
        val process = ProcessBuilder(aapt.absolutePath, "dump", "badging", apk.get().asFile.absolutePath)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { output }
        val required = output.lineSequence().map(String::trim).filter { it.startsWith("uses-feature:") || it.startsWith("uses-implied-feature:") }
            .filter { it.contains("android.hardware.camera") }.toList()
        check(required.isEmpty()) { "Manual-only devices excluded by release APK: $required" }
        listOf("android.hardware.camera", "android.hardware.camera.autofocus", "android.hardware.camera.any").forEach { feature ->
            check(output.contains("uses-feature-not-required: name='$feature'")) { "Missing optional feature $feature" }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view) {
        exclude(group = "androidx.camera", module = "camera-video")
    }
    implementation(libs.litert) {
        // Model delivery is intentionally out of scope: the verified interpreter/model are bundled.
        exclude(group = "com.google.android.play", module = "ai-delivery")
    }

    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
