package com.maxmfonseca.pontinho.recognition

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionEngine
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionEngineFactory
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionResult
import com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability
import com.maxmfonseca.pontinho.domain.recognition.RecognitionModelMetadata
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed class CardRecognitionException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause) {
    class Closed : CardRecognitionException("Card recognition engine is closed")

    class InferenceFailed : CardRecognitionException("Card recognition failed")
}

internal interface MediaPipeDetectorRunner : AutoCloseable {
    fun detect(bitmap: Bitmap): List<RawCardDetection>
}

internal fun interface MediaPipeDetectorRunnerFactory {
    fun create(model: ByteArray, metadata: RecognitionModelMetadata): MediaPipeDetectorRunner
}

class MediaPipeCardRecognitionEngineFactory internal constructor(
    private val runnerFactory: MediaPipeDetectorRunnerFactory,
) : CardRecognitionEngineFactory {
    constructor(context: Context) : this(
        MediaPipeDetectorRunnerFactory { model, metadata ->
            MediaPipeObjectDetectorRunner.create(context, model, metadata)
        },
    )

    override fun create(model: ByteArray, metadata: RecognitionModelMetadata): CardRecognitionEngine =
        MediaPipeCardRecognitionEngine(runnerFactory.create(model, metadata), metadata)
}

internal class MediaPipeCardRecognitionEngine(
    private val runner: MediaPipeDetectorRunner,
    private val metadata: RecognitionModelMetadata,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nanoTime: () -> Long = System::nanoTime,
) : CardRecognitionEngine {
    private val lock = Any()

    @Volatile
    private var closed = false

    override val availability: RecognitionAvailability = RecognitionAvailability.Available(metadata)

    override suspend fun recognize(bitmap: Bitmap): CardRecognitionResult = try {
        withContext(dispatcher) {
            val coroutineContext = currentCoroutineContext()
            coroutineContext.ensureActive()
            synchronized(lock) {
                if (closed) throw CardRecognitionException.Closed()
                coroutineContext.ensureActive()
                val startedAt = nanoTime()
                val rawDetections = runner.detect(bitmap)
                coroutineContext.ensureActive()
                val durationMillis = ((nanoTime() - startedAt).coerceAtLeast(0L)) / NANOS_PER_MILLISECOND
                val processed = postProcessor(metadata).process(rawDetections)
                CardRecognitionResult(
                    candidates = processed.cards,
                    durationMillis = durationMillis,
                    modelVersion = metadata.modelVersion,
                )
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (closed: CardRecognitionException.Closed) {
        throw closed
    } catch (_: Exception) {
        throw CardRecognitionException.InferenceFailed()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runner.close()
        }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val LOW_CONFIDENCE_MARGIN = 0.1f
        const val EDGE_THRESHOLD = 0.03f

        fun postProcessor(metadata: RecognitionModelMetadata) = CardDetectionPostProcessor(
            DetectionPostProcessingConfig(
                confidenceThreshold = metadata.confidenceThreshold,
                lowConfidenceThreshold = (metadata.confidenceThreshold + LOW_CONFIDENCE_MARGIN).coerceAtMost(1f),
                iouThreshold = metadata.iouThreshold,
                edgeThreshold = EDGE_THRESHOLD,
            ),
        )
    }
}

private class MediaPipeObjectDetectorRunner(
    private val detector: ObjectDetector,
) : MediaPipeDetectorRunner {
    override fun detect(bitmap: Bitmap): List<RawCardDetection> {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        return BitmapImageBuilder(bitmap).build().use { image ->
            detector.detect(image).detections().mapNotNull { detection ->
                val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                val bounds = detection.boundingBox()
                RawCardDetection(
                    // MediaPipe Category.index() is already zero-based; do not offset it.
                    classIndex = category.index(),
                    confidence = category.score(),
                    left = bounds.left / width,
                    top = bounds.top / height,
                    right = bounds.right / width,
                    bottom = bounds.bottom / height,
                )
            }
        }
    }

    override fun close() = detector.close()

    companion object {
        fun create(
            context: Context,
            model: ByteArray,
            metadata: RecognitionModelMetadata,
        ): MediaPipeObjectDetectorRunner {
            val modelBuffer = ByteBuffer.allocateDirect(model.size)
                .order(ByteOrder.nativeOrder())
                .put(model)
                .apply { rewind() }
            val baseOptions = BaseOptions.builder()
                .setModelAssetBuffer(modelBuffer)
                .setDelegate(Delegate.CPU)
                .build()
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(metadata.confidenceThreshold)
                .setMaxResults(CardClassIndex.labels.size)
                .build()
            return MediaPipeObjectDetectorRunner(ObjectDetector.createFromOptions(context, options))
        }
    }
}
