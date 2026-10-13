package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import com.maxmfonseca.pontinho.BuildConfig
import com.maxmfonseca.pontinho.domain.recognition.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal typealias SingleStageRecognitionAssetReader =
    suspend () -> Pair<ByteArray?, ByteArray?>

/** Asset I/O/hash verification runs off-main; querying availability never creates a native detector.
 * One provider-owned initialization is started only by recognition. Cancelling a screen cancels its
 * await/inference, not the reusable detector. Closing the provider also disposes a late native result.
 */
class VerifiedCardRecognitionEngineProvider internal constructor(
    private val readAssets: SingleStageRecognitionAssetReader,
    private val factory: CardRecognitionEngineFactory?,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CardRecognitionEngineProvider {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var closed = false
    private var native: CardRecognitionEngine? = null
    private val state = MutableStateFlow<RecognitionAvailability>(RecognitionAvailability.Checking)
    override val availability = state.asStateFlow()
    private val verified = scope.async {
        val (modelSource, releaseSource) = try { readAssets() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null to null }
        // Own immutable snapshots through verification and native construction.
        val model = modelSource?.copyOf()
        val result = RecognitionAssetVerifier(allowExperimental = BuildConfig.DEBUG)
            .verify(model, releaseSource?.copyOf())
        state.value = if (result is RecognitionAvailability.Available && factory == null)
            unavailable() else result
        VerifiedAssets(model, state.value)
    }
    private val initialized = scope.async(start = CoroutineStart.LAZY) {
        val (model, result) = verified.await()
        check(result is RecognitionAvailability.Available && model != null && factory != null) {
            "Card recognition is unavailable"
        }
        val created = try { factory.create(model, result.metadata as YoloRecognitionMetadata) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: LinkageError) { state.value = unavailable(); throw CardRecognitionException.InferenceFailed() }
        catch (_: Exception) { state.value = unavailable(); throw CardRecognitionException.InferenceFailed() }
        val accepted = synchronized(lock) {
            if (closed) false else { native = created; true }
        }
        if (!accepted) {
            created.close()
            throw CancellationException("Recognition provider closed")
        }
        state.value = created.availability
        created
    }
    private val borrowed = object : CardRecognitionEngine {
        override val availability get() = state.value
        override suspend fun recognize(bitmap: Bitmap): CardRecognitionResult {
            synchronized(lock) { check(!closed) { "Recognition provider is closed" } }
            return initialized.await().recognize(bitmap)
        }
        override fun close() = Unit // The process provider owns the native resource.
    }

    override fun get(): CardRecognitionEngine = synchronized(lock) {
        check(!closed) { "Recognition provider is closed" }
        borrowed
    }

    /** Call off-main when a native inference may be active. */
    override fun close() {
        val engine = synchronized(lock) {
            if (closed) return
            closed = true
            native
        }
        scope.cancel()
        engine?.close()
    }

    private fun unavailable() = RecognitionAvailability.Unavailable(RecognitionUnavailableReason.ENGINE_NOT_INSTALLED)

    private data class VerifiedAssets(
        val model: ByteArray?,
        val availability: RecognitionAvailability,
    )
}
