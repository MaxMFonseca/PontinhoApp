package com.maxmfonseca.pontinho.recognition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.maxmfonseca.pontinho.domain.recognition.YoloRecognitionMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.tensorflow.lite.DataType

class LiteRtYoloRunnerContractTest {
    @Test
    fun `FP32 and internally INT8 candidates both require FP32 graph boundaries`() {
        listOf("fp32", "int8").forEach { quantization ->
            val interpreter = FakeInterpreter()
            LiteRtYoloRunner(metadata().copy(quantization = quantization), interpreter).use { runner ->
                val first = runner.run(prepared(.25f, .5f, .75f))
                assertArrayEquals(intArrayOf(1, 56, 8400), first.shape)
                assertEquals(.875f, first.values[0])
                assertEquals(1, interpreter.allocations)
                assertArrayEquals(floatArrayOf(.25f, .5f, .75f), interpreter.lastInput.copyOf(3), 0f)

                first.values[0] = 0f
                assertEquals(.875f, runner.run(prepared(.25f, .5f, .75f)).values[0])
                assertEquals(1, interpreter.allocations)
            }
            assertEquals(1, interpreter.closes)
        }
    }

    @Test
    fun `runtime metadata must exactly match one unquantized FP32 input and output`() {
        val validInput = descriptor(intArrayOf(1, 640, 640, 3), 1_228_800)
        val validOutput = descriptor(intArrayOf(1, 56, 8400), 470_400)
        val invalid = listOf(
            FakeInterpreter(inputDescriptors = emptyList()),
            FakeInterpreter(outputDescriptors = emptyList()),
            FakeInterpreter(inputDescriptors = listOf(validInput, validInput)),
            FakeInterpreter(outputDescriptors = listOf(validOutput, validOutput)),
            FakeInterpreter(inputDescriptors = listOf(validInput.copy(shape = intArrayOf(1, 320, 320, 3)))),
            FakeInterpreter(outputDescriptors = listOf(validOutput.copy(shape = intArrayOf(1, 8400, 56)))),
            FakeInterpreter(inputDescriptors = listOf(validInput.copy(dataType = DataType.INT8))),
            FakeInterpreter(outputDescriptors = listOf(validOutput.copy(dataType = DataType.INT8))),
            FakeInterpreter(inputDescriptors = listOf(validInput.copy(byteSize = validInput.byteSize - 4))),
            FakeInterpreter(outputDescriptors = listOf(validOutput.copy(byteSize = validOutput.byteSize - 4))),
            FakeInterpreter(inputDescriptors = listOf(validInput.copy(quantizationScale = .25f))),
            FakeInterpreter(outputDescriptors = listOf(validOutput.copy(quantizationZeroPoint = 3))),
        )

        invalid.forEach { interpreter ->
            assertThrows(IllegalArgumentException::class.java) {
                LiteRtYoloRunner(metadata(), interpreter)
            }
            assertEquals(1, interpreter.closes)
        }
    }

    @Test
    fun `run rejects malformed prepared tensors before native invocation`() {
        val cases = listOf(
            prepared(.1f).copy(shape = intArrayOf(1, 1, 1, 3)),
            prepared(.1f).copy(pixels = floatArrayOf(.1f, .2f)),
            prepared(Float.NaN, .2f, .3f),
            prepared(-.1f, .2f, .3f),
            prepared(.1f, .2f, 1.1f),
        )
        cases.forEach { input ->
            val interpreter = FakeInterpreter()
            LiteRtYoloRunner(metadata(), interpreter).use { runner ->
                assertThrows(IllegalArgumentException::class.java) { runner.run(input) }
                assertEquals(0, interpreter.runs)
            }
        }
    }

    private fun prepared(vararg prefix: Float): PreparedYoloImage {
        val pixels = FloatArray(1_228_800)
        prefix.copyInto(pixels)
        return PreparedYoloImage(
            pixels = pixels,
            shape = intArrayOf(1, 640, 640, 3),
            geometry = LetterboxGeometry.forSource(1, 1),
        )
    }

    private fun metadata() = YoloRecognitionMetadata(
        modelSha256 = YoloBundleFixture.modelHash,
        modelSizeBytes = YoloBundleFixture.model.size.toLong(),
        quantization = "fp32",
        labels = YoloBundleFixture.labels,
        inputWidth = 640,
        inputHeight = 640,
        outputShape = listOf(1, 56, 8400),
        normalizationScale = 1.0 / 255.0,
        normalizationOffset = 0.0,
        confidenceThreshold = .3,
        iouThreshold = .45,
        maxCards = 10,
        datasetReceiptSha256 = "d".repeat(64),
        datasetAuditSha256 = "e".repeat(64),
        holdoutReceiptSha256 = "1".repeat(64),
        exportInvocationSha256 = "2".repeat(64),
        validationEvaluationSha256 = "4".repeat(64),
        testEvaluationSha256 = "5".repeat(64),
        debugOnly = true,
        productionApproved = false,
    )

    private fun descriptor(shape: IntArray, elements: Int) = LiteRtTensorDescriptor(
        shape = shape,
        dataType = DataType.FLOAT32,
        byteSize = elements * Float.SIZE_BYTES,
        quantizationScale = 0f,
        quantizationZeroPoint = 0,
    )

    private inner class FakeInterpreter(
        override val inputDescriptors: List<LiteRtTensorDescriptor> = listOf(
            descriptor(intArrayOf(1, 640, 640, 3), 1_228_800),
        ),
        override val outputDescriptors: List<LiteRtTensorDescriptor> = listOf(
            descriptor(intArrayOf(1, 56, 8400), 470_400),
        ),
        private val expectedInputElements: Int = 1_228_800,
    ) : LiteRtInterpreter {
        var allocations = 0
        var runs = 0
        var closes = 0
        var lastInput = FloatArray(0)

        override fun allocateTensors() {
            allocations++
        }

        override fun run(input: ByteBuffer, output: ByteBuffer) {
            runs++
            lastInput = FloatArray(expectedInputElements)
            input.order(ByteOrder.nativeOrder()).asFloatBuffer().get(lastInput)
            output.order(ByteOrder.nativeOrder()).asFloatBuffer().put(0, .875f)
        }

        override fun close() {
            closes++
        }
    }
}
