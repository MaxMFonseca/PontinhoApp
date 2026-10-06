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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * Owns one native detector for the process lifetime.
 *
 * [close] stops new admission immediately. It is deliberately synchronous and blocks its caller
 * only while an already-active native call finishes; lifecycle code should therefore call it off
 * the main thread when inference may still be running.
 */
internal class MediaPipeCardRecognitionEngine(
    private val runner: MediaPipeDetectorRunner,
    private val metadata: RecognitionModelMetadata,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nanoTime: () -> Long = System::nanoTime,
) : CardRecognitionEngine {
    private val stateLock = Any()
    private val inferenceMutex = Mutex()
    private val closeCompletion = CompletableDeferred<Unit>()

    @Volatile
    private var closed = false
    private var activeNativeCall: CompletableDeferred<Unit>? = null

    override val availability: RecognitionAvailability = RecognitionAvailability.Available(metadata)

    override suspend fun recognize(bitmap: Bitmap): CardRecognitionResult = try {
        withContext(dispatcher) {
            val coroutineContext = currentCoroutineContext()
            coroutineContext.ensureActive()
            inferenceMutex.withLock {
                coroutineContext.ensureActive()
                val nativeCompletion = synchronized(stateLock) {
                    if (closed) throw CardRecognitionException.Closed()
                    CompletableDeferred<Unit>().also { activeNativeCall = it }
                }
                var startedAt = 0L
                val rawDetections = try {
                    startedAt = nanoTime()
                    runner.detect(bitmap)
                } finally {
                    synchronized(stateLock) {
                        if (activeNativeCall === nativeCompletion) activeNativeCall = null
                    }
                    nativeCompletion.complete(Unit)
                }
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
    } catch (_: LinkageError) {
        throw CardRecognitionException.InferenceFailed()
    } catch (_: Exception) {
        throw CardRecognitionException.InferenceFailed()
    }

    override fun close() {
        val closePlan = synchronized(stateLock) {
            if (closed) {
                ClosePlan.AwaitExisting
            } else {
                closed = true
                ClosePlan.Perform(activeNativeCall)
            }
        }
        when (closePlan) {
            ClosePlan.AwaitExisting -> runBlocking { closeCompletion.await() }
            is ClosePlan.Perform -> {
                try {
                    closePlan.activeNativeCall?.let { active -> runBlocking { active.await() } }
                    runner.close()
                    closeCompletion.complete(Unit)
                } catch (failure: Throwable) {
                    closeCompletion.completeExceptionally(failure)
                    throw failure
                }
            }
        }
    }

    private sealed interface ClosePlan {
        data object AwaitExisting : ClosePlan
        data class Perform(val activeNativeCall: CompletableDeferred<Unit>?) : ClosePlan
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
            val setup = MediaPipeObjectDetectorOptionsFactory.create(model, metadata)
            return MediaPipeObjectDetectorRunner(
                ObjectDetector.createFromOptions(context, setup.buildOptions()),
            )
        }
    }
}

internal data class MediaPipeObjectDetectorSetup(
    val modelBuffer: ByteBuffer,
    val delegate: Delegate,
    val runningMode: RunningMode,
    val scoreThreshold: Float,
    val maxResults: Int,
) {
    fun buildOptions(): ObjectDetector.ObjectDetectorOptions {
        val baseOptions = BaseOptions.builder()
            .setModelAssetBuffer(modelBuffer)
            .setDelegate(delegate)
            .build()
        return ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(runningMode)
            .setScoreThreshold(scoreThreshold)
            .setMaxResults(maxResults)
            .build()
    }
}

internal object MediaPipeObjectDetectorOptionsFactory {
    fun create(model: ByteArray, metadata: RecognitionModelMetadata): MediaPipeObjectDetectorSetup {
        val modelBuffer = ByteBuffer.allocateDirect(model.size)
            .order(ByteOrder.nativeOrder())
            .put(model)
            .apply { rewind() }
        return MediaPipeObjectDetectorSetup(
            modelBuffer = modelBuffer,
            delegate = Delegate.CPU,
            runningMode = RunningMode.IMAGE,
            scoreThreshold = metadata.confidenceThreshold,
            maxResults = CardClassIndex.labels.size,
        )
    }
}
