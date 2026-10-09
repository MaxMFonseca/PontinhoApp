package com.maxmfonseca.pontinho.recognition

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maxmfonseca.pontinho.domain.recognition.*
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaPipeCardRecognitionEngineTest {
    @Test fun packagedScannerIsUnavailableBecauseQualifiedTwoStageAssetsAreMissing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun asset(name: String): ByteArray? = try { context.assets.open(name).use { it.readBytes() } }
            catch (_: IOException) { null }
        val detector = asset("card_detector.tflite")
        val classifier = asset("card_classifier.tflite")
        val release = asset("card_recognition.release.json")
        // This is the current blocker, not evidence that a complete bundle failed quality.
        // Once promoted, replace with licensed multi-card fixture inference and canonical nonempty
        // predictions. Never use black pixels as accuracy evidence.
        assertNull("No qualified classifier has been promoted", classifier)
        assertNull("No joint release evidence has been promoted", release)
        assertEquals(RecognitionAvailability.Unavailable(RecognitionUnavailableReason.MISSING_ASSETS),
            RecognitionAssetVerifier().verify(detector, classifier, release))
    }

    @Test fun productionOptionsKeepAllDetectorBoxesAndClassifierScores() {
        val bytes = byteArrayOf(1, 2, 3)
        val detector = MediaPipeCardDetectorSetup(directModelBuffer(bytes), .5f)
        val classifier = MediaPipeCardClassifierSetup(directModelBuffer(bytes))
        assertTrue(detector.modelBuffer.isDirect)
        assertTrue(classifier.modelBuffer.isDirect)
        assertEquals(-1, detector.maxResults)
        detector.buildOptions()
        classifier.buildOptions()
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
