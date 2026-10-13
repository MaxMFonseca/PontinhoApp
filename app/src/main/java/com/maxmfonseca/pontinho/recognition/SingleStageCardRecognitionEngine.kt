package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import com.maxmfonseca.pontinho.domain.model.Card
import com.maxmfonseca.pontinho.domain.model.Rank
import com.maxmfonseca.pontinho.domain.model.Suit
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionEngine
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionEngineFactory
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionFailure
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionResult
import com.maxmfonseca.pontinho.domain.recognition.NormalizedRect
import com.maxmfonseca.pontinho.domain.recognition.RecognizedCard
import com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability
import com.maxmfonseca.pontinho.domain.recognition.RecognitionWarning
import com.maxmfonseca.pontinho.domain.recognition.YoloRecognitionMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SingleStageCardRecognitionEngineFactory internal constructor(
    private val runnerFactory: (ByteArray, YoloRecognitionMetadata) -> YoloRunner,
    private val dispatcher: CoroutineDispatcher,
) : CardRecognitionEngineFactory {
    constructor() : this(
        runnerFactory = LiteRtYoloRunner::create,
        dispatcher = Dispatchers.Default,
    )

    override fun create(model: ByteArray, metadata: YoloRecognitionMetadata): CardRecognitionEngine =
        SingleStageCardRecognitionEngine(
            runner = runnerFactory(model.copyOf(), metadata),
            metadata = metadata,
            dispatcher = dispatcher,
        )
}

/**
 * Serializes the complete single-stage transaction. Closing rejects new admission immediately,
 * waits for the admitted synchronous inference to finish, and then releases the LiteRT interpreter.
 */
internal class SingleStageCardRecognitionEngine(
    private val runner: YoloRunner,
    private val metadata: YoloRecognitionMetadata,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nanoTime: () -> Long = System::nanoTime,
    private val prepare: (Bitmap) -> PreparedYoloImage = {
        YoloLetterbox.prepare(it, metadata.inputWidth)
    },
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
                    val input = prepare(bitmap)
                    context.ensureActive()
                    val output = runner.run(input)
                    context.ensureActive()
                    val detections = try {
                        YoloPostProcessor.decode(
                            output = output.values,
                            shape = output.shape,
                            geometry = input.geometry,
                            settings = YoloPostprocessSettings(
                                confidenceThreshold = metadata.confidenceThreshold,
                                iouThreshold = metadata.iouThreshold,
                                maxCards = metadata.maxCards,
                            ),
                        )
                    } catch (tooMany: TooManyYoloDetections) {
                        throw CardRecognitionFailure.TooManyCards(tooMany.count)
                    }
                    if (detections.isEmpty()) throw CardRecognitionFailure.NoCards()
                    val cards = detections.map(::recognizedCard)
                    context.ensureActive()
                    CardRecognitionResult(
                        candidates = cards,
                        durationMillis = (nanoTime() - start).coerceAtLeast(0) / 1_000_000,
                        modelVersion = metadata.modelVersion,
                    )
                } finally {
                    synchronized(stateLock) {
                        if (active === completion) active = null
                    }
                    completion.complete(Unit)
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: CardRecognitionFailure) {
        throw failure
    } catch (closed: CardRecognitionException.Closed) {
        throw closed
    } catch (_: LinkageError) {
        throw CardRecognitionException.InferenceFailed()
    } catch (_: Exception) {
        throw CardRecognitionException.InferenceFailed()
    }

    override fun close() {
        val pending: CompletableDeferred<Unit>?
        val ownsClose: Boolean
        synchronized(stateLock) {
            ownsClose = !closed
            closed = true
            pending = active
        }
        if (!ownsClose) {
            runBlocking { closeCompletion.await() }
            return
        }
        try {
            pending?.let { runBlocking { it.await() } }
            runner.close()
            closeCompletion.complete(Unit)
        } catch (failure: Throwable) {
            closeCompletion.completeExceptionally(failure)
            throw failure
        }
    }

    private fun recognizedCard(detection: RawYoloDetection): RecognizedCard {
        require(metadata.labels.getOrNull(detection.classIndex) == detection.label)
        val bounds = NormalizedRect(detection.left, detection.top, detection.right, detection.bottom)
        val warnings = buildSet {
            if (detection.confidence < LOW_CONFIDENCE_THRESHOLD) add(RecognitionWarning.LOW_CONFIDENCE)
            if (
                bounds.left <= EDGE_THRESHOLD ||
                bounds.top <= EDGE_THRESHOLD ||
                bounds.right >= 1f - EDGE_THRESHOLD ||
                bounds.bottom >= 1f - EDGE_THRESHOLD
            ) {
                add(RecognitionWarning.NEAR_IMAGE_EDGE)
            }
        }
        return RecognizedCard(cardForLabel(detection.label), bounds, detection.confidence, warnings)
    }

    private fun cardForLabel(label: String): Card.Suited {
        require(label.length in 2..3)
        val suit = when (label.last()) {
            'C' -> Suit.CLUBS
            'D' -> Suit.DIAMONDS
            'H' -> Suit.HEARTS
            'S' -> Suit.SPADES
            else -> error("Unknown suit")
        }
        val rank = when (label.dropLast(1)) {
            "A" -> Rank.ACE
            "2" -> Rank.TWO
            "3" -> Rank.THREE
            "4" -> Rank.FOUR
            "5" -> Rank.FIVE
            "6" -> Rank.SIX
            "7" -> Rank.SEVEN
            "8" -> Rank.EIGHT
            "9" -> Rank.NINE
            "10" -> Rank.TEN
            "J" -> Rank.JACK
            "Q" -> Rank.QUEEN
            "K" -> Rank.KING
            else -> error("Unknown rank")
        }
        return Card.Suited(rank, suit)
    }

    private companion object {
        const val LOW_CONFIDENCE_THRESHOLD = .60f
        const val EDGE_THRESHOLD = .03f
    }
}
