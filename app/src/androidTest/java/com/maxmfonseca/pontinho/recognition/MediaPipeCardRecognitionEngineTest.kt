package com.maxmfonseca.pontinho.recognition

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability
import com.maxmfonseca.pontinho.domain.recognition.RecognitionModelMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaPipeCardRecognitionEngineTest {
    @Test
    fun packagedExperimentalModelIsRejectedWhenMeasuredQualityIsBelowTheFloor() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val model = context.assets.open("card_detector.tflite").use { it.readBytes() }
        val release = context.assets.open("card_detector.release.json").use { it.readBytes() }
        val availability = RecognitionAssetVerifier(allowExperimental = true).verify(model, release)
        assertTrue(availability is RecognitionAvailability.Unavailable)
    }

    @Test
    fun productionOptionsUseVerifiedDirectModelBytesCpuAndImageMode() {
        val bytes = byteArrayOf(1, 2, 3)
        val setup = MediaPipeObjectDetectorOptionsFactory.create(bytes, metadata())

        assertTrue(setup.modelBuffer.isDirect)
        assertEquals(listOf<Byte>(1, 2, 3), setup.modelBuffer.asReadOnlyBuffer().let { buffer ->
            buildList {
                while (buffer.hasRemaining()) add(buffer.get())
            }
        })
        assertEquals(Delegate.CPU, setup.delegate)
        assertEquals(RunningMode.IMAGE, setup.runningMode)
        assertEquals(0.5f, setup.scoreThreshold)
        assertEquals(52, setup.maxResults)
        setup.buildOptions()
    }

    @Test
    fun realRunnerReachesNativeModelValidationWithoutLinkageFailure() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val failure = runCatching {
            MediaPipeCardRecognitionEngineFactory(context).create(
                model = byteArrayOf(1, 2, 3),
                metadata = metadata(),
            )
        }.exceptionOrNull()

        assertTrue("Invalid TFLite bytes must fail model loading", failure != null)
        assertFalse("Pinned MediaPipe native libraries must link", failure is LinkageError)
    }

    private fun metadata() = RecognitionModelMetadata(
        modelVersion = "linkage-test",
        modelSha256 = "a".repeat(64),
        labels = CardClassIndex.labels,
        inputWidth = 320,
        inputHeight = 320,
        confidenceThreshold = 0.5f,
        iouThreshold = 0.4f,
    )
}
