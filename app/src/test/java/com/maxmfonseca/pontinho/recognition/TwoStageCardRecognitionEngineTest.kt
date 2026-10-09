package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import com.maxmfonseca.pontinho.domain.recognition.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class TwoStageCardRecognitionEngineTest {
    @Test fun `duration includes both stages and reports verified bundle version`() = runBlocking {
        val times = ArrayDeque(listOf(2_000_000_000L, 2_123_999_999L))
        val metadata = metadata()
        val engine = TwoStageCardRecognitionEngine(Detector(listOf(box(.1f))), Classifier(), metadata, Dispatchers.Unconfined, times::removeFirst)
        val result = engine.recognize(bitmap())
        assertEquals(123L, result.durationMillis)
        assertEquals(metadata.version, result.modelVersion)
    }

    @Test fun `close during classifier waits for crop cleanup and closes both stages once`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val classifier = Classifier(beforeReturn = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
        val detector = Detector(listOf(box(.1f)))
        val engine = engine(detector, classifier, dispatcher = Dispatchers.Default)
        val job = async { engine.recognize(bitmap()) }
        withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
        val closer = Thread { engine.close(); finished.countDown() }.apply { start() }
        try { assertFalse(finished.await(100, TimeUnit.MILLISECONDS)) } finally { release.countDown() }
        job.await()
        closer.join(5000)
        assertEquals(0L, finished.count)
        engine.close()
        assertEquals(1, detector.closed)
        assertEquals(1, classifier.closed)
        assertTrue(classifier.crops.single().isRecycled)
    }
    @Test fun `quantized probability mass uses verified output scale before calibration`() = runBlocking {
        val scores = FloatArray(52) { 5f / 256f }
        val quantized = metadata().let { it.copy(classifier = it.classifier.copy(quantizationVariant = "int8", outputQuantizationScale = 1.0 / 256)) }
        val result = engine(Detector(listOf(box(.1f))), Classifier(scores), quantized).recognize(bitmap())
        assertEquals(1f / 52, result.candidates.single().confidence, .000001f)
        assertThrows(CardRecognitionException.InferenceFailed::class.java) {
            runBlocking { engine(Detector(listOf(box(.1f))), Classifier(scores)).recognize(bitmap()) }
        }
        Unit
    }
    @Test fun `NMS collapses physical duplicates preserving separate equal cards and spatial order`() = runBlocking {
        val detector = Detector(listOf(box(.6f), box(.1f), box(.101f, .8f)))
        val classifier = Classifier()
        val source = bitmap()
        val result = engine(detector, classifier).recognize(source)
        assertEquals(listOf(.1f, .6f), result.candidates.map { it.bounds.left })
        assertEquals(listOf(CardClassIndex.cardAt(0), CardClassIndex.cardAt(0)), result.candidates.map { it.card })
        assertEquals(2, classifier.crops.size)
        assertTrue(classifier.crops.all { it.isRecycled })
        assertFalse(source.isRecycled)
    }

    @Test fun `temperature uses all 52 probabilities before min confidence and warnings`() = runBlocking {
        val probabilities = FloatArray(52).apply { this[0] = .8f; this[1] = .2f }
        val classifier = Classifier(probabilities)
        val metadata = metadata().let { it.copy(classifier = it.classifier.copy(calibrationTemperature = 2f)) }
        val result = engine(Detector(listOf(box(0f, .9f))), classifier, metadata).recognize(bitmap())
        assertEquals(2f / 3f, result.candidates.single().confidence, .0001f)
        assertEquals(setOf(RecognitionWarning.LOW_CONFIDENCE, RecognitionWarning.NEAR_IMAGE_EDGE), result.candidates.single().warnings)
        val capped = engine(Detector(listOf(box(.1f, .55f))), Classifier()).recognize(bitmap())
        assertEquals(.55f, capped.candidates.single().confidence, .0001f)
    }

    @Test fun `ten differently oriented edge boxes classify original resolution crops without truncation`() = runBlocking {
        val boxes = (0..9).map { i ->
            val x = (i % 5) * .2f
            val y = (i / 5) * .5f
            RawCardBox(.9f, x, y, x + .1f, y + if (i % 2 == 0) .3f else .15f)
        }
        val classifier = Classifier()
        val result = engine(Detector(boxes.reversed()), classifier).recognize(bitmap())
        assertEquals(10, result.candidates.size)
        assertEquals(boxes.map { it.left to it.top }, result.candidates.map { it.bounds.left to it.bounds.top })
        assertTrue(classifier.sizes.all { it.first >= 200 && it.second >= 150 })
        assertTrue(result.candidates.first().warnings.contains(RecognitionWarning.NEAR_IMAGE_EDGE))
    }

    @Test fun `zero cards and eleven cards fail with typed guidance before classification`() {
        val classifier = Classifier()
        assertThrows(CardRecognitionFailure.NoCards::class.java) {
            runBlocking { engine(Detector(emptyList()), classifier).recognize(bitmap()) }
        }
        val boxes = (0..10).map { RawCardBox(.9f, it / 12f, .1f, (it + .5f) / 12f, .4f) }
        val error = assertThrows(CardRecognitionFailure.TooManyCards::class.java) {
            runBlocking { engine(Detector(boxes), classifier).recognize(bitmap()) }
        }
        assertEquals(11, error.detectedCount)
        assertTrue(classifier.crops.isEmpty())
    }

    @Test fun `incomplete and subthreshold boxes never become partial confident hand`() = runBlocking {
        val boxes = listOf(box(.1f), RawCardBox(.9f, -.1f, .1f, .2f, .4f), box(.6f, .45f))
        val result = engine(Detector(boxes), Classifier()).recognize(bitmap())
        assertEquals(1, result.candidates.size)
        assertTrue(result.candidates.single().warnings.contains(RecognitionWarning.LOW_CONFIDENCE))
    }

    @Test fun `malformed classifier output fails privately and recycles crop`() {
        val classifier = Classifier(FloatArray(51))
        val source = bitmap()
        val error = assertThrows(CardRecognitionException.InferenceFailed::class.java) {
            runBlocking { engine(Detector(listOf(box(.1f))), classifier).recognize(source) }
        }
        assertNull(error.cause)
        assertTrue(classifier.crops.single().isRecycled)
        assertFalse(source.isRecycled)
    }

    @Test fun `cancellation during classifier discards results and recycles owned crop`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val classifier = Classifier(beforeReturn = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
        val source = bitmap()
        val engine = engine(Detector(listOf(box(.1f), box(.6f))), classifier, dispatcher = Dispatchers.Default)
        val job = async { engine.recognize(source) }
        withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
        job.cancel()
        release.countDown()
        assertThrows(CancellationException::class.java) { runBlocking { job.await() } }
        assertEquals(1, classifier.crops.size)
        assertTrue(classifier.crops.single().isRecycled)
        assertFalse(source.isRecycled)
        engine.close()
    }

    @Test fun `factory closes detector if classifier construction fails`() {
        val detector = Detector(listOf(box(.1f)))
        val factory = TwoStageCardRecognitionEngineFactory({ _, _ -> detector }, { _, _ -> error("native") })
        assertThrows(IllegalStateException::class.java) { factory.create(byteArrayOf(1), byteArrayOf(2), metadata()) }
        assertEquals(1, detector.closed)
    }

    private fun engine(detector: CardDetectorRunner, classifier: CardClassifierRunner, metadata: RecognitionModelBundleMetadata = metadata(), dispatcher: CoroutineDispatcher = Dispatchers.Unconfined) =
        TwoStageCardRecognitionEngine(detector, classifier, metadata, dispatcher)

    private fun metadata() = (RecognitionAssetVerifier().verify(BundleFixture.detector, BundleFixture.classifier, BundleFixture.bytes()) as RecognitionAvailability.Available)
        .metadata.let { it as RecognitionModelBundleMetadata }.copy(classifierThreshold = .7f)

    private fun bitmap() = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
    private fun box(left: Float, score: Float = .9f) = RawCardBox(score, left, .1f, left + .15f, .4f)

    private class Detector(private val boxes: List<RawCardBox>) : CardDetectorRunner {
        var closed = 0
        override fun detect(bitmap: Bitmap) = boxes
        override fun close() { closed++ }
    }
    private class Classifier(private val probabilities: FloatArray = FloatArray(52).apply { this[0] = 1f }, private val beforeReturn: () -> Unit = {}) : CardClassifierRunner {
        var closed = 0
        val crops = mutableListOf<Bitmap>()
        val sizes = mutableListOf<Pair<Int, Int>>()
        override fun classify(bitmap: Bitmap): RawCardClassification {
            crops += bitmap
            sizes += bitmap.width to bitmap.height
            beforeReturn()
            return RawCardClassification(probabilities)
        }
        override fun close() { closed++ }
    }
}
