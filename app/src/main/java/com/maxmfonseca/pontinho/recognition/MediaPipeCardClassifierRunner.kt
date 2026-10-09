package com.maxmfonseca.pontinho.recognition

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import com.maxmfonseca.pontinho.domain.recognition.RecognitionModelBundleMetadata
import java.nio.ByteBuffer

internal class MediaPipeCardClassifierRunner private constructor(
    private val classifier: ImageClassifier,
    private val modelBuffer: ByteBuffer,
) : CardClassifierRunner {
    override fun classify(bitmap: Bitmap): RawCardClassification = withOwnedMediaPipeImage(bitmap) { image ->
        val categories = classifier.classify(image).classificationResult().classifications().single().categories()
        canonicalClassification(categories)
    }
    override fun close() = classifier.close()

    companion object {
        fun create(context: Context, model: ByteArray, metadata: RecognitionModelBundleMetadata): MediaPipeCardClassifierRunner {
            require(metadata.classifier.labels == CardClassIndex.labels)
            val setup = MediaPipeCardClassifierSetup(directModelBuffer(model))
            return MediaPipeCardClassifierRunner(ImageClassifier.createFromOptions(context, setup.buildOptions()), setup.modelBuffer)
        }
    }
}

internal fun canonicalClassification(categories: List<Category>): RawCardClassification {
    require(categories.size == 52 && categories.map { it.index() }.toSet() == (0..51).toSet())
    val scores = FloatArray(52)
    categories.forEach { scores[it.index()] = it.score() }
    return RawCardClassification(scores)
}

internal data class MediaPipeCardClassifierSetup(val modelBuffer: ByteBuffer) {
    fun buildOptions(): ImageClassifier.ImageClassifierOptions = ImageClassifier.ImageClassifierOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(modelBuffer).setDelegate(Delegate.CPU).build())
        .setRunningMode(RunningMode.IMAGE)
        .setMaxResults(52)
        .setScoreThreshold(0f) // Calibration needs every probability, including zeroes.
        .build()
}
