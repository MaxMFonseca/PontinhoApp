package com.maxmfonseca.pontinho.recognition

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.maxmfonseca.pontinho.domain.recognition.RecognitionModelMetadata

@RunWith(AndroidJUnit4::class)
class MediaPipeCardRecognitionEngineTest {
    @Test
    fun pinnedMediaPipeBuildsImageModeCpuOptionsOnDevice() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
        val baseOptions = BaseOptions.builder()
            .setModelAssetBuffer(
                java.nio.ByteBuffer.allocateDirect(1).apply {
                    put(1.toByte())
                    rewind()
                },
            )
            .setDelegate(Delegate.CPU)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(0.5f)
            .setMaxResults(52)
            .build()

        assertEquals(ObjectDetector.ObjectDetectorOptions::class.java, options.javaClass.superclass)
    }

    @Test
    fun realRunnerReachesNativeModelValidationWithoutLinkageFailure() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val failure = runCatching {
            MediaPipeCardRecognitionEngineFactory(context).create(
                model = byteArrayOf(1, 2, 3),
                metadata = RecognitionModelMetadata(
                    modelVersion = "linkage-test",
                    modelSha256 = "a".repeat(64),
                    labels = CardClassIndex.labels,
                    inputWidth = 320,
                    inputHeight = 320,
                    confidenceThreshold = 0.5f,
                    iouThreshold = 0.4f,
                ),
            )
        }.exceptionOrNull()

        assertTrue("Invalid TFLite bytes must fail model loading", failure != null)
        assertFalse("Pinned MediaPipe native libraries must link", failure is LinkageError)
    }
}
