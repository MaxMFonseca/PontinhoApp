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
        compose = true
        buildConfig = false
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
    inputs.files(configurations.named("releaseRuntimeClasspath"))
    doLast {
        val forbiddenArtifactPrefixes = setOf(
            "transport-api-",
            "transport-runtime-",
            "transport-backend-cct-",
            "firebase-encoders-json-",
            "firebase-encoders-proto-",
            "okhttp-",
            "retrofit-",
            "volley-",
            "httpclient-",
        )
        val offenders = inputs.files
            .map { it.name }
            .filter { name -> forbiddenArtifactPrefixes.any(name::startsWith) }
            .sorted()
        check(offenders.isEmpty()) {
            "Offline scanner contains networking/telemetry artifacts: ${offenders.joinToString()}"
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
    implementation(libs.mediapipe.tasks.vision) {
        exclude(group = "com.google.android.datatransport")
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
