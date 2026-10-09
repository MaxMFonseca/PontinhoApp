package com.maxmfonseca.pontinho.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maxmfonseca.pontinho.domain.recognition.*
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaPipeCardRecognitionEngineTest {
    @Test fun packagedDebugScannerVerifiesAndLoadsBothExperimentalModels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun asset(name: String): ByteArray? = try { context.assets.open(name).use { it.readBytes() } }
            catch (_: IOException) { null }
        val detector = asset("card_detector.tflite")
        val classifier = asset("card_classifier.tflite")
        val release = asset("card_recognition.release.json")
        assertNotNull("Debug detector must be packaged", detector)
        assertNotNull("Debug classifier must be packaged", classifier)
        assertNotNull("Debug experiment metadata must be packaged", release)
        val availability = RecognitionAssetVerifier(allowExperimental = true)
            .verify(detector, classifier, release)
        assertTrue(availability is RecognitionAvailability.Available)
        val metadata = (availability as RecognitionAvailability.Available)
            .metadata as RecognitionModelBundleMetadata
        assertTrue(metadata.experimental)
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        MediaPipeCardDetectorRunner.create(context, requireNotNull(detector), metadata).use { runner ->
            runner.detect(bitmap)
        }
        MediaPipeCardClassifierRunner.create(context, requireNotNull(classifier), metadata).use { runner ->
            val probabilities = runner.classify(bitmap).probabilities
            assertEquals(52, probabilities.size)
            assertEquals(1f, probabilities.sum(), .001f)
        }
        bitmap.recycle()
    }

    @Test fun productionOptionsKeepAllDetectorBoxesAndClassifierScores() {
        val bytes = byteArrayOf(1, 2, 3)
        val detector = MediaPipeCardDetectorSetup(directModelBuffer(bytes), .5f)
        val classifier = MediaPipeCardClassifierSetup(directModelBuffer(bytes))
        assertTrue(detector.modelBuffer.isDirect)
        assertTrue(classifier.modelBuffer.isDirect)
        assertEquals(-1, detector.maxResults)
        detector.buildOptions()
        val actual = classifier.buildOptions()
        // Package-private native option getters: inspect the actual options without JNI conversion.
        fun option(name: String): Any? = com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier.ImageClassifierOptions::class.java
            .getDeclaredMethod(name).apply { isAccessible = true }.invoke(actual)
        assertEquals(java.util.Optional.of(52), option("maxResults"))
        assertEquals(java.util.Optional.of(0f), option("scoreThreshold"))
        assertEquals(emptyList<String>(), option("categoryAllowlist"))
        assertEquals(emptyList<String>(), option("categoryDenylist"))
        assertEquals(com.google.mediapipe.tasks.vision.core.RunningMode.IMAGE, option("runningMode"))
        val delegate = com.google.mediapipe.tasks.core.BaseOptions::class.java.getDeclaredMethod("delegate")
            .apply { isAccessible = true }.invoke(option("baseOptions"))
        assertEquals(com.google.mediapipe.tasks.core.Delegate.CPU, delegate)
    }

    @Test fun bothNativeTasksReachModelValidationWithoutLinkageFailure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stage = RecognitionStageMetadata("a".repeat(64), 3, CardClassIndex.labels, 224, 224, "float16")
        val metadata = RecognitionModelBundleMetadata("linkage-test", stage.copy(labels = listOf("card"), inputWidth = 320, inputHeight = 320),
            stage, CardClassIndex.labels, .5f, .6f, .4f, .1f, 10)
        val failures = listOf(
            runCatching { MediaPipeCardDetectorRunner.create(context, byteArrayOf(1, 2, 3), metadata).close() }.exceptionOrNull(),
            runCatching { MediaPipeCardClassifierRunner.create(context, byteArrayOf(1, 2, 3), metadata).close() }.exceptionOrNull(),
        )
        failures.forEach { failure ->
            assertNotNull("Invalid TFLite bytes must fail model loading", failure)
            assertFalse("Pinned MediaPipe native libraries must link", failure is LinkageError)
        }
    }
}
