package com.maxmfonseca.pontinho.recognition

import com.maxmfonseca.pontinho.domain.recognition.YoloRecognitionMetadata
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

internal data class YoloTensorOutput(
    val values: FloatArray,
    val shape: IntArray,
)

internal interface YoloRunner : AutoCloseable {
    fun run(input: PreparedYoloImage): YoloTensorOutput
}

internal data class LiteRtTensorDescriptor(
    val shape: IntArray,
    val dataType: DataType,
    val byteSize: Int,
    val quantizationScale: Float,
    val quantizationZeroPoint: Int,
)

internal interface LiteRtInterpreter : AutoCloseable {
    val inputDescriptors: List<LiteRtTensorDescriptor>
    val outputDescriptors: List<LiteRtTensorDescriptor>

    fun allocateTensors()
    fun run(input: ByteBuffer, output: ByteBuffer)
}

internal class LiteRtYoloRunner(
    private val metadata: YoloRecognitionMetadata,
    private val interpreter: LiteRtInterpreter,
) : YoloRunner {
    private val lock = Any()
    private val inputShape = intArrayOf(1, metadata.inputHeight, metadata.inputWidth, 3)
    private val outputShape = metadata.outputShape.toIntArray()
    private val inputElements = inputShape.elementCount()
    private val outputElements = outputShape.elementCount()
    private val inputBuffer: ByteBuffer
    private val outputBuffer: ByteBuffer
    private var closed = false

    init {
        try {
            require(metadata.quantization == "fp32" || metadata.quantization == "int8")
            interpreter.allocateTensors()
            require(interpreter.inputDescriptors.size == 1) { "LiteRT model must expose one input" }
            require(interpreter.outputDescriptors.size == 1) { "LiteRT model must expose one output" }
            validateBoundary(interpreter.inputDescriptors.single(), inputShape, inputElements, "input")
            validateBoundary(interpreter.outputDescriptors.single(), outputShape, outputElements, "output")
            inputBuffer = directFloatBuffer(inputElements)
            outputBuffer = directFloatBuffer(outputElements)
        } catch (failure: Throwable) {
            try {
                interpreter.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    override fun run(input: PreparedYoloImage): YoloTensorOutput = synchronized(lock) {
        check(!closed) { "LiteRT runner is closed" }
        require(input.shape.contentEquals(inputShape)) { "prepared input shape does not match model" }
        require(input.pixels.size == inputElements) { "prepared input length does not match model" }
        require(input.pixels.all { it.isFinite() && it in 0f..1f }) {
            "prepared input must contain finite normalized floats"
        }

        inputBuffer.clear()
        inputBuffer.asFloatBuffer().put(input.pixels)
        outputBuffer.clear()
        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()
        val owned = FloatArray(outputElements)
        outputBuffer.asFloatBuffer().get(owned)
        YoloTensorOutput(owned, outputShape.copyOf())
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            interpreter.close()
        }
    }

    private fun validateBoundary(
        actual: LiteRtTensorDescriptor,
        expectedShape: IntArray,
        expectedElements: Int,
        name: String,
    ) {
        require(actual.shape.contentEquals(expectedShape)) { "LiteRT $name shape does not match metadata" }
        require(actual.dataType == DataType.FLOAT32) { "LiteRT $name must be FP32" }
        require(actual.byteSize == Math.multiplyExact(expectedElements, Float.SIZE_BYTES)) {
            "LiteRT $name byte size does not match shape"
        }
        require(actual.quantizationScale == 0f && actual.quantizationZeroPoint == 0) {
            "LiteRT $name boundary must be unquantized FP32"
        }
    }

    companion object {
        fun create(model: ByteArray, metadata: YoloRecognitionMetadata): LiteRtYoloRunner {
            require(model.isNotEmpty()) { "LiteRT model is empty" }
            val ownedModel = ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder())
            ownedModel.put(model).rewind()
            val options = Interpreter.Options().setUseXNNPACK(true)
            val native = Interpreter(ownedModel, options)
            return LiteRtYoloRunner(metadata, NativeLiteRtInterpreter(native, ownedModel))
        }

        private fun directFloatBuffer(elements: Int): ByteBuffer = ByteBuffer
            .allocateDirect(Math.multiplyExact(elements, Float.SIZE_BYTES))
            .order(ByteOrder.nativeOrder())
    }
}

private class NativeLiteRtInterpreter(
    private val interpreter: Interpreter,
    @Suppress("unused") private val retainedModel: ByteBuffer,
) : LiteRtInterpreter {
    override val inputDescriptors: List<LiteRtTensorDescriptor>
        get() = (0 until interpreter.inputTensorCount).map { index -> interpreter.getInputTensor(index).descriptor() }
    override val outputDescriptors: List<LiteRtTensorDescriptor>
        get() = (0 until interpreter.outputTensorCount).map { index -> interpreter.getOutputTensor(index).descriptor() }

    override fun allocateTensors() = interpreter.allocateTensors()
    override fun run(input: ByteBuffer, output: ByteBuffer) = interpreter.run(input, output)
    override fun close() = interpreter.close()
}

private fun org.tensorflow.lite.Tensor.descriptor(): LiteRtTensorDescriptor {
    val quantization = quantizationParams()
    return LiteRtTensorDescriptor(
        shape = shape().copyOf(),
        dataType = dataType(),
        byteSize = numBytes(),
        quantizationScale = quantization.scale,
        quantizationZeroPoint = quantization.zeroPoint,
    )
}

private fun IntArray.elementCount(): Int = fold(1, Math::multiplyExact)
