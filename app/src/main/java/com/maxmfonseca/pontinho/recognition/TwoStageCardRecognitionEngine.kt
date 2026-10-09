package com.maxmfonseca.pontinho.recognition

import android.content.Context
import android.graphics.Bitmap
import com.maxmfonseca.pontinho.domain.recognition.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

internal data class RawCardBox(val confidence: Float, val left: Float, val top: Float, val right: Float, val bottom: Float)
internal data class RawCardClassification(val probabilities: FloatArray)
internal interface CardDetectorRunner : AutoCloseable { fun detect(bitmap: Bitmap): List<RawCardBox> }
internal interface CardClassifierRunner : AutoCloseable { fun classify(bitmap: Bitmap): RawCardClassification }

class TwoStageCardRecognitionEngineFactory internal constructor(
    private val detectorFactory: (ByteArray, RecognitionModelBundleMetadata) -> CardDetectorRunner,
    private val classifierFactory: (ByteArray, RecognitionModelBundleMetadata) -> CardClassifierRunner,
) : CardRecognitionEngineFactory {
    constructor(context: Context) : this(
        { bytes, metadata -> MediaPipeCardDetectorRunner.create(context.applicationContext, bytes, metadata) },
        { bytes, metadata -> MediaPipeCardClassifierRunner.create(context.applicationContext, bytes, metadata) },
    )

    override fun create(detectorModel: ByteArray, classifierModel: ByteArray, metadata: RecognitionModelBundleMetadata): CardRecognitionEngine {
        val detector = detectorFactory(detectorModel, metadata)
        try {
            return TwoStageCardRecognitionEngine(detector, classifierFactory(classifierModel, metadata), metadata)
        } catch (failure: Throwable) {
            try { detector.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}

/** Serializes the whole two-stage transaction. Close rejects admission immediately and waits for
 * the admitted transaction to release every native call and crop; callers should close off-main. */
internal class TwoStageCardRecognitionEngine(
    private val detector: CardDetectorRunner,
    private val classifier: CardClassifierRunner,
    private val metadata: RecognitionModelBundleMetadata,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nanoTime: () -> Long = System::nanoTime,
) : CardRecognitionEngine {
    private val stateLock = Any()
    private val inferenceMutex = Mutex()
    private val closeCompletion = CompletableDeferred<Unit>()
    private var closed = false
    private var active: CompletableDeferred<Unit>? = null
    override val availability = RecognitionAvailability.Available(metadata)

    override suspend fun recognize(bitmap: Bitmap): CardRecognitionResult = try {
        withContext(dispatcher) {
            val context = currentCoroutineContext()
            inferenceMutex.withLock {
                context.ensureActive()
                val completion = synchronized(stateLock) {
                    if (closed) throw CardRecognitionException.Closed()
                    CompletableDeferred<Unit>().also { active = it }
                }
                try {
                    val start = nanoTime()
                    val raw = detector.detect(bitmap)
                    context.ensureActive()
                    var uncertainScene = false
                    val valid = raw.mapNotNull { box ->
                        require(box.confidence.isFinite() && box.confidence in 0f..1f)
                        require(listOf(box.left, box.top, box.right, box.bottom).all(Float::isFinite))
                        if (box.confidence < metadata.detectorThreshold) {
                            if (box.confidence >= metadata.detectorThreshold * .8f) uncertainScene = true
                            null
                        } else if (box.left < 0f || box.top < 0f || box.right > 1f || box.bottom > 1f || box.left >= box.right || box.top >= box.bottom) {
                            uncertainScene = true
                            null
                        } else box
                    }
                    val kept = mutableListOf<RawCardBox>()
                    valid.sortedWith(compareByDescending<RawCardBox> { it.confidence }.thenBy { it.left }.thenBy { it.top }.thenBy { it.right }.thenBy { it.bottom })
                        .forEach { candidate -> if (kept.none { iou(it, candidate) > metadata.iouThreshold }) kept += candidate }
                    if (kept.isEmpty()) throw CardRecognitionFailure.NoCards()
                    if (kept.size > metadata.maxCards) throw CardRecognitionFailure.TooManyCards(kept.size)
                    val cards = kept.sortedWith(compareBy<RawCardBox> { it.top }.thenBy { it.left }.thenBy { it.bottom }.thenBy { it.right }).map { box ->
                        context.ensureActive()
                        val bounds = NormalizedRect(box.left, box.top, box.right, box.bottom)
                        val crop = CardCropper.crop(bitmap, bounds, metadata.cropMargin)
                        val classification = try {
                            classifier.classify(crop).also { context.ensureActive() }
                        } finally { crop.recycle() }
                        val scores = calibratedScores(classification.probabilities, metadata.classifier)
                        // First canonical index wins ties, just like numpy.argmax in the evaluator.
                        val index = scores.indices.maxBy { scores[it] }
                        val confidence = minOf(box.confidence, scores[index].toFloat())
                        val warnings = buildSet {
                            if (uncertainScene || confidence < metadata.classifierThreshold) add(RecognitionWarning.LOW_CONFIDENCE)
                            if (box.left <= .03f || box.top <= .03f || box.right >= .97f || box.bottom >= .97f) add(RecognitionWarning.NEAR_IMAGE_EDGE)
                        }
                        RecognizedCard(requireNotNull(CardClassIndex.cardAt(index)), bounds, confidence, warnings)
                    }
                    context.ensureActive()
                    CardRecognitionResult(cards, (nanoTime() - start).coerceAtLeast(0) / 1_000_000, metadata.version)
                } finally {
                    synchronized(stateLock) { if (active === completion) active = null }
                    completion.complete(Unit)
                }
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: CardRecognitionFailure) { throw failure
    } catch (closed: CardRecognitionException.Closed) { throw closed
    } catch (_: LinkageError) { throw CardRecognitionException.InferenceFailed()
    } catch (_: Exception) { throw CardRecognitionException.InferenceFailed() }

    override fun close() {
        val pending: CompletableDeferred<Unit>?
        val ownsClose: Boolean
        synchronized(stateLock) {
            ownsClose = !closed
            closed = true
            pending = active
        }
        if (!ownsClose) { runBlocking { closeCompletion.await() }; return }
        try {
            pending?.let { runBlocking { it.await() } }
            var failure: Throwable? = null
            try { classifier.close() } catch (error: Throwable) { failure = error }
            try { detector.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            failure?.let { throw it }
            closeCompletion.complete(Unit)
        } catch (failure: Throwable) {
            closeCompletion.completeExceptionally(failure)
            throw failure
        }
    }

    private fun iou(a: RawCardBox, b: RawCardBox): Float {
        val overlap = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f) *
            (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        return overlap / ((a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - overlap)
    }

    private fun calibratedScores(raw: FloatArray, stage: RecognitionStageMetadata): DoubleArray {
        require(raw.size == 52 && raw.all { it.isFinite() && it in 0f..1f })
        val mass = raw.sumOf(Float::toDouble)
        val epsilon = 1.1920928955078125e-7
        val tolerance = 52 * epsilon / (1 - 52 * epsilon) + 52 * stage.outputQuantizationScale / 2
        require(mass > 0 && abs(mass - 1) <= tolerance)
        val temperature = stage.calibrationTemperature.toDouble()
        require(temperature.isFinite() && temperature > 0)
        val logits = DoubleArray(52) { ln(maxOf(raw[it] / mass, 1e-12)) / temperature }
        val maximum = logits.max()
        val scores = DoubleArray(52) { exp(logits[it] - maximum) }
        val total = scores.sum()
        return DoubleArray(52) { scores[it] / total }
    }
}
