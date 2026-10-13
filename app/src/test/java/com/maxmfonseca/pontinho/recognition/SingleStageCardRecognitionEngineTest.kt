package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import com.maxmfonseca.pontinho.domain.model.Card
import com.maxmfonseca.pontinho.domain.model.Rank
import com.maxmfonseca.pontinho.domain.model.Suit
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionFailure
import com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability
import com.maxmfonseca.pontinho.domain.recognition.RecognitionWarning
import com.maxmfonseca.pontinho.domain.recognition.YoloRecognitionMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SingleStageCardRecognitionEngineTest {
    @Test
    fun `maps canonical YOLO labels directly and preserves decoder spatial order`() = runBlocking {
        val engine = engine(output(
            detection(anchor = 0, classIndex = 37, score = .9f, left = .65f),
            detection(anchor = 1, classIndex = 0, score = .8f, left = .10f),
        ))

        val result = engine.recognize(bitmap())

        assertEquals(
            listOf(
                Card.Suited(Rank.TEN, Suit.CLUBS),
                Card.Suited(Rank.ACE, Suit.DIAMONDS),
            ),
            result.candidates.map { it.card },
        )
        assertEquals(listOf(.10f, .65f), result.candidates.map { it.bounds.left })
        assertEquals(metadata().modelVersion, result.modelVersion)
    }

    @Test
    fun `marks low-confidence and near-edge detections for mandatory review`() = runBlocking {
        val result = engine(output(
            detection(anchor = 0, classIndex = 3, score = .55f, left = .01f),
            detection(anchor = 1, classIndex = 4, score = .9f, left = .50f),
        )).recognize(bitmap())

        assertEquals(
            setOf(RecognitionWarning.LOW_CONFIDENCE, RecognitionWarning.NEAR_IMAGE_EDGE),
            result.candidates[0].warnings,
        )
        assertEquals(emptySet<RecognitionWarning>(), result.candidates[1].warnings)
    }

    @Test
    fun `zero and eleven valid detections become typed capture failures`() {
        assertThrows(CardRecognitionFailure.NoCards::class.java) {
            runBlocking { engine(output()).recognize(bitmap()) }
        }
        val eleven = (0..10).map { index ->
            detection(anchor = index, classIndex = index, score = .9f, left = .02f + index * .085f, width = .04f)
        }
        val error = assertThrows(CardRecognitionFailure.TooManyCards::class.java) {
            runBlocking { engine(output(*eleven.toTypedArray())).recognize(bitmap()) }
        }
        assertEquals(11, error.detectedCount)
    }

    @Test
    fun `malformed raw tensor is hidden behind inference failure`() {
        val engine = engine(YoloTensorOutput(FloatArray(1), intArrayOf(1, 1, 1)))

        assertThrows(CardRecognitionException.InferenceFailed::class.java) {
            runBlocking { engine.recognize(bitmap()) }
        }
    }

    @Test
    fun `duration covers preprocessing inference and decoding`() = runBlocking {
        val times = ArrayDeque(listOf(2_000_000_000L, 2_123_999_999L))
        val engine = engine(output(detection()), nanoTime = times::removeFirst)

        assertEquals(123L, engine.recognize(bitmap()).durationMillis)
    }

    @Test
    fun `factory implements verified single-model domain contract`() {
        var capturedModel = byteArrayOf()
        val metadata = metadata()
        val factory = SingleStageCardRecognitionEngineFactory(
            runnerFactory = { model, _ -> capturedModel = model; FakeRunner(output(detection())) },
            dispatcher = Dispatchers.Unconfined,
        )

        val engine = factory.create(byteArrayOf(1, 2, 3), metadata)

        assertEquals(RecognitionAvailability.Available(metadata), engine.availability)
        assertEquals(listOf<Byte>(1, 2, 3), capturedModel.toList())
    }

    private fun engine(
        output: YoloTensorOutput,
        nanoTime: () -> Long = { 0L },
    ) = SingleStageCardRecognitionEngine(
        runner = FakeRunner(output),
        metadata = metadata(),
        dispatcher = Dispatchers.Unconfined,
        nanoTime = nanoTime,
        prepare = { prepared() },
    )

    private fun prepared() = PreparedYoloImage(
        pixels = FloatArray(0),
        shape = intArrayOf(1, 640, 640, 3),
        geometry = LetterboxGeometry.forSource(100, 100),
    )

    private fun metadata() = (RecognitionAssetVerifier(allowExperimental = true)
        .verify(YoloBundleFixture.model, YoloBundleFixture.bytes()) as RecognitionAvailability.Available)
        .metadata as YoloRecognitionMetadata

    private fun bitmap() = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

    private data class Detection(
        val anchor: Int,
        val classIndex: Int,
        val score: Float,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    )

    private fun detection(
        anchor: Int = 0,
        classIndex: Int = 0,
        score: Float = .9f,
        left: Float = .1f,
        top: Float = .2f,
        width: Float = .2f,
        height: Float = .3f,
    ) = Detection(anchor, classIndex, score, left, top, width, height)

    private fun output(vararg detections: Detection): YoloTensorOutput {
        val values = FloatArray(56 * 8400)
        detections.forEach { detection ->
            values[detection.anchor] = detection.left + detection.width / 2f
            values[8400 + detection.anchor] = detection.top + detection.height / 2f
            values[2 * 8400 + detection.anchor] = detection.width
            values[3 * 8400 + detection.anchor] = detection.height
            values[(4 + detection.classIndex) * 8400 + detection.anchor] = detection.score
        }
        return YoloTensorOutput(values, intArrayOf(1, 56, 8400))
    }

    private class FakeRunner(private val output: YoloTensorOutput) : YoloRunner {
        override fun run(input: PreparedYoloImage) = output
        override fun close() = Unit
    }
}
