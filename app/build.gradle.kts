import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.zip.ZipFile

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
        resources.directories.add("src/main/assets")
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
tasks.register("verifyOptionalCameraReleaseApk") {
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
        check(required.isEmpty()) { "Devices without camera hardware are excluded by release APK: $required" }
        listOf("android.hardware.camera", "android.hardware.camera.autofocus", "android.hardware.camera.any").forEach { feature ->
            check(output.contains("uses-feature-not-required: name='$feature'")) { "Missing optional feature $feature" }
        }
    }
}

tasks.register("verifyReleaseRecognitionAssets") {
    dependsOn("assembleRelease", "bundleRelease")
    val sourceModel = layout.projectDirectory.file("src/main/assets/card_detector.tflite")
    val sourceMetadata = layout.projectDirectory.file("src/main/assets/card_recognition.release.json")
    val apk = layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk")
    val bundle = layout.buildDirectory.file("outputs/bundle/release/app-release.aab")
    inputs.files(sourceModel, sourceMetadata, apk, bundle)
    doLast {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        val approvedHash = "01034db88037605250fd3d78b49ee791a7711b218305e44ebe4ff3c02e2ad277"
        val modelBytes = sourceModel.asFile.readBytes()
        val metadataBytes = sourceMetadata.asFile.readBytes()
        val metadata = JsonSlurper().parse(metadataBytes) as Map<*, *>
        val model = metadata["model"] as? Map<*, *> ?: error("Production metadata model is missing")
        check(metadata["candidateStatus"] == "OWNER_APPROVED_V1") { "Release metadata is not owner approved" }
        check(metadata["productionApproved"] == true) { "Release metadata productionApproved must be true" }
        check(model["sha256"] == approvedHash) { "Release metadata model hash is not the approved hash" }
        check((model["sizeBytes"] as? Number)?.toLong() == 12_838_331L) { "Release metadata model size is invalid" }
        check(modelBytes.size == 12_838_331 && sha256(modelBytes) == approvedHash) {
            "Main recognition model is not the owner-approved artifact"
        }
        fun verifyArchive(file: File, modelEntry: String, metadataEntry: String) {
            ZipFile(file).use { zip ->
                val packagedModel = zip.getEntry(modelEntry)?.let { zip.getInputStream(it).use { stream -> stream.readBytes() } }
                    ?: error("${file.name} is missing $modelEntry")
                val packagedMetadata = zip.getEntry(metadataEntry)?.let { zip.getInputStream(it).use { stream -> stream.readBytes() } }
                    ?: error("${file.name} is missing $metadataEntry")
                check(packagedModel.contentEquals(modelBytes)) { "${file.name} contains different model bytes" }
                check(packagedMetadata.contentEquals(metadataBytes)) { "${file.name} contains different production metadata" }
                check("DEBUG_ONLY" !in packagedMetadata.decodeToString()) { "${file.name} contains debug-only metadata" }
            }
        }
        verifyArchive(apk.get().asFile, "assets/card_detector.tflite", "assets/card_recognition.release.json")
        verifyArchive(bundle.get().asFile, "base/assets/card_detector.tflite", "base/assets/card_recognition.release.json")
    }
}

tasks.register("verifyReleaseNativeAlignment") {
    dependsOn("assembleRelease")
    val sdkDirectory = androidComponents.sdkComponents.sdkDirectory
    val buildToolsVersion = android.buildToolsVersion
    val apk = layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk")
    inputs.file(apk)
    doLast {
        fun parsePtLoadAlignments(output: String): List<Long> {
            val llvm = Regex("Type: PT_LOAD[\\s\\S]*?Alignment: (\\d+)")
                .findAll(output)
                .map { it.groupValues[1].toLong() }
                .toList()
            val gnu = output.lineSequence()
                .map(String::trim)
                .filter { it.startsWith("LOAD ") }
                .mapNotNull { line ->
                    line.substringAfterLast(' ').let { value ->
                        runCatching {
                            if (value.startsWith("0x", ignoreCase = true)) value.drop(2).toLong(16)
                            else value.toLong()
                        }.getOrNull()
                    }
                }
                .toList()
            return (llvm + gnu).distinct()
        }
        val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val zipalign = sdkDirectory.get().file("build-tools/$buildToolsVersion/zipalign$suffix").asFile
        val alignment = ProcessBuilder(zipalign.absolutePath, "-c", "-P", "16", "4", apk.get().asFile.absolutePath)
            .redirectErrorStream(true).start()
        val alignmentOutput = alignment.inputStream.bufferedReader().use { it.readText() }
        check(alignment.waitFor() == 0) { "APK 16 KB zip alignment failed:\n$alignmentOutput" }

        val sdkNdkTools = sdkDirectory.get().dir("ndk").asFile
            .listFiles().orEmpty().flatMap { ndk ->
                ndk.resolve("toolchains/llvm/prebuilt").listFiles().orEmpty().flatMap { prebuilt ->
                    listOf(prebuilt.resolve("bin/llvm-readelf$suffix"), prebuilt.resolve("bin/llvm-readobj$suffix"))
                }
            }
        val pathTools = System.getenv("PATH").orEmpty().split(File.pathSeparator).flatMap { directory ->
            listOf(File(directory, "llvm-readelf$suffix"), File(directory, "llvm-readobj$suffix"))
        }
        val fallbackTools = if (suffix.isNotEmpty()) {
            listOf(File("C:/Program Files/LLVM/bin/llvm-readobj.exe"))
        } else emptyList()
        val elfTool = (sdkNdkTools + pathTools + fallbackTools).firstOrNull(File::isFile)
            ?: error("No llvm-readelf/llvm-readobj tool is installed; native 16 KB ELF alignment was not verified")

        var nativeLibraryCount = 0
        ZipFile(apk.get().asFile).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".so") }.forEach { entry ->
                nativeLibraryCount++
                val extracted = temporaryDir.resolve(entry.name)
                extracted.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> extracted.outputStream().use(input::copyTo) }
                val option = if (elfTool.name.startsWith("llvm-readelf")) "--program-headers" else "--program-headers"
                val inspection = ProcessBuilder(elfTool.absolutePath, option, extracted.absolutePath)
                    .redirectErrorStream(true).start()
                val output = inspection.inputStream.bufferedReader().use { it.readText() }
                check(inspection.waitFor() == 0) { "ELF inspection failed for ${entry.name}:\n$output" }
                val alignments = parsePtLoadAlignments(output)
                check(alignments.isNotEmpty()) { "No PT_LOAD alignment found for ${entry.name} using ${elfTool.name}" }
                check(alignments.all { it >= 16_384L }) {
                    "${entry.name} has PT_LOAD alignment below 16 KB: $alignments"
                }
            }
        }
        check(nativeLibraryCount > 0) { "Release APK contains no native libraries to verify" }
        logger.lifecycle("Verified 16 KB zip and ELF alignment for $nativeLibraryCount native libraries with ${elfTool.absolutePath}")
    }
}

tasks.register("verifyNativeAlignmentParser") {
    doLast {
        fun parsePtLoadAlignments(output: String): List<Long> {
            val llvm = Regex("Type: PT_LOAD[\\s\\S]*?Alignment: (\\d+)")
                .findAll(output)
                .map { it.groupValues[1].toLong() }
                .toList()
            val gnu = output.lineSequence()
                .map(String::trim)
                .filter { it.startsWith("LOAD ") }
                .mapNotNull { line ->
                    line.substringAfterLast(' ').let { value ->
                        runCatching {
                            if (value.startsWith("0x", ignoreCase = true)) value.drop(2).toLong(16)
                            else value.toLong()
                        }.getOrNull()
                    }
                }
                .toList()
            return (llvm + gnu).distinct()
        }
        val llvm = """
            ProgramHeader {
              Type: PT_LOAD (0x1)
              Alignment: 16384
            }
        """.trimIndent()
        val gnu = """
            Type           Offset   VirtAddr   PhysAddr   FileSiz MemSiz  Flg Align
            LOAD           0x000000 0x00000000 0x00000000 0x001000 0x001000 R E 0x4000
        """.trimIndent()
        check(parsePtLoadAlignments(llvm) == listOf(16_384L)) { "LLVM PT_LOAD parser regression" }
        check(parsePtLoadAlignments(gnu) == listOf(16_384L)) { "GNU PT_LOAD parser regression" }
    }
}

tasks.register("verifyUnsignedReleaseCandidate") {
    dependsOn(
        "testDebugUnitTest",
        "lintRelease",
        "verifyOfflineScannerDependencies",
        "verifyOptionalCameraReleaseApk",
        "verifyReleaseRecognitionAssets",
        "verifyReleaseNativeAlignment",
        "verifyNativeAlignmentParser",
    )
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
