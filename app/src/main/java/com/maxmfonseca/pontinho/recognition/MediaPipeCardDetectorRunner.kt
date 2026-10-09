package com.maxmfonseca.pontinho.recognition

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.maxmfonseca.pontinho.domain.recognition.RecognitionModelBundleMetadata
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** BitmapImageContainer.close recycles its bitmap in MediaPipe 1.1.0. Always hand it owned pixels. */
internal fun <T> withOwnedMediaPipeImage(source: Bitmap, width: Int = source.width, height: Int = source.height, action: (MPImage) -> T): T {
    // Bound temporary native pixels to the model input; never duplicate a full capture.
    val owned = ModelImageResizer.resize(source, width, height)
    return try { BitmapImageBuilder(owned).build().use(action) }
    finally { if (!owned.isRecycled) owned.recycle() }
}

internal fun directModelBuffer(bytes: ByteArray): ByteBuffer = ByteBuffer.allocateDirect(bytes.size)
    .order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }

internal class MediaPipeCardDetectorRunner private constructor(
    private val detector: ObjectDetector,
    // Retain direct bytes for the entire native task lifetime.
    private val modelBuffer: ByteBuffer,
    private val inputWidth: Int,
    private val inputHeight: Int,
) : CardDetectorRunner {
    override fun detect(bitmap: Bitmap): List<RawCardBox> = withOwnedMediaPipeImage(bitmap, inputWidth, inputHeight) { image ->
        detector.detect(image).detections().map { detection ->
            val category = detection.categories().single()
            require(category.index() == 0)
            val bounds = detection.boundingBox()
            nativeCardBox(category.score(), bounds, image.width, image.height)
        }
    }
    override fun close() = detector.close()

    companion object {
        fun create(context: Context, model: ByteArray, metadata: RecognitionModelBundleMetadata): MediaPipeCardDetectorRunner {
            val setup = MediaPipeCardDetectorSetup(directModelBuffer(model), metadata.detectorThreshold)
            return MediaPipeCardDetectorRunner(ObjectDetector.createFromOptions(context, setup.buildOptions()), setup.modelBuffer,
                metadata.detector.inputWidth, metadata.detector.inputHeight)
        }
    }
}

internal data class MediaPipeCardDetectorSetup(val modelBuffer: ByteBuffer, val scoreThreshold: Float) {
    val maxResults = -1 // Native maximum: eleven must remain detectable, never silently cut to ten.
    fun buildOptions(): ObjectDetector.ObjectDetectorOptions = ObjectDetector.ObjectDetectorOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(modelBuffer).setDelegate(Delegate.CPU).build())
        .setRunningMode(RunningMode.IMAGE)
        // Include the evaluator's 80%-of-floor uncertainty band.
        .setScoreThreshold(scoreThreshold * .8f)
        .setMaxResults(maxResults)
        .build()
}
