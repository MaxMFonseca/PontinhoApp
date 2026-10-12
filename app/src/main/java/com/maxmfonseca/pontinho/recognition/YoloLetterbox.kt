package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.min
import kotlin.math.round

data class LetterboxGeometry(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val inputWidth: Int,
    val inputHeight: Int,
    val resizedWidth: Int,
    val resizedHeight: Int,
    val scale: Double,
    val padX: Double,
    val padY: Double,
    val padRight: Int,
    val padBottom: Int,
) {
    internal fun validate(expectedInputSize: Int? = null) {
        if (
            sourceWidth <= 0 ||
            sourceHeight <= 0 ||
            inputWidth <= 0 ||
            inputHeight <= 0 ||
            inputWidth != inputHeight ||
            expectedInputSize?.let { inputWidth != it } == true ||
            resizedWidth <= 0 ||
            resizedHeight <= 0 ||
            !scale.isFinite() ||
            !padX.isFinite() ||
            !padY.isFinite() ||
            scale <= 0.0 ||
            padX < 0.0 ||
            padY < 0.0 ||
            padRight < 0 ||
            padBottom < 0
        ) {
            throw YoloDecodeException("invalid letterbox geometry")
        }
        val expectedScale = min(inputWidth.toDouble() / sourceWidth, inputHeight.toDouble() / sourceHeight)
        val horizontalPadding = inputWidth - resizedWidth
        val verticalPadding = inputHeight - resizedHeight
        val expectedPadX = round(horizontalPadding / 2.0 - 0.1).toInt()
        val expectedPadY = round(verticalPadding / 2.0 - 0.1).toInt()
        if (
            kotlin.math.abs(scale - expectedScale) > GEOMETRY_TOLERANCE ||
            resizedWidth != round(sourceWidth * scale).toInt() ||
            resizedHeight != round(sourceHeight * scale).toInt()
        ) {
            throw YoloDecodeException("letterbox geometry is inconsistent")
        }
        if (
            padX != expectedPadX.toDouble() ||
            padY != expectedPadY.toDouble() ||
            padRight != round(horizontalPadding / 2.0 + 0.1).toInt() ||
            padBottom != round(verticalPadding / 2.0 + 0.1).toInt()
        ) {
            throw YoloDecodeException("letterbox padding split is inconsistent")
        }
    }

    companion object {
        private const val GEOMETRY_TOLERANCE = 1e-5

        fun forSource(sourceWidth: Int, sourceHeight: Int, inputSize: Int = YOLO_INPUT_SIZE): LetterboxGeometry {
            require(sourceWidth > 0 && sourceHeight > 0 && inputSize > 0) {
                "letterbox dimensions must be positive"
            }
            val scale = min(inputSize.toDouble() / sourceWidth, inputSize.toDouble() / sourceHeight)
            val resizedWidth = round(sourceWidth * scale).toInt()
            val resizedHeight = round(sourceHeight * scale).toInt()
            val horizontalPadding = inputSize - resizedWidth
            val verticalPadding = inputSize - resizedHeight
            val padX = round(horizontalPadding / 2.0 - 0.1).toInt()
            val padY = round(verticalPadding / 2.0 - 0.1).toInt()
            return LetterboxGeometry(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                inputWidth = inputSize,
                inputHeight = inputSize,
                resizedWidth = resizedWidth,
                resizedHeight = resizedHeight,
                scale = scale,
                padX = padX.toDouble(),
                padY = padY.toDouble(),
                padRight = round(horizontalPadding / 2.0 + 0.1).toInt(),
                padBottom = round(verticalPadding / 2.0 + 0.1).toInt(),
            )
        }
    }
}

data class PreparedYoloImage(
    val pixels: FloatArray,
    val shape: IntArray,
    val geometry: LetterboxGeometry,
)

object YoloLetterbox {
    private const val PADDING_CHANNEL = 114
    private const val RESIZE_COEFFICIENT_BITS = 11
    private const val RESIZE_COEFFICIENT_SCALE = 1 shl RESIZE_COEFFICIENT_BITS

    /**
     * Produces one NHWC RGB float tensor using Ultralytics' byte-space OpenCV
     * INTER_LINEAR resize, RGB channel order, centered padding, then /255 normalization.
     */
    fun prepare(bitmap: Bitmap, size: Int = YOLO_INPUT_SIZE): PreparedYoloImage {
        require(!bitmap.isRecycled) { "source bitmap is recycled" }
        require(size > 0) { "letterbox dimensions must be positive" }
        val geometry = LetterboxGeometry.forSource(bitmap.width, bitmap.height, size)
        val sourcePixels = IntArray(Math.multiplyExact(bitmap.width, bitmap.height))
        bitmap.getPixels(sourcePixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        require(sourcePixels.all { Color.alpha(it) == 255 }) { "YOLO input must be opaque RGB" }

        val output = FloatArray(Math.multiplyExact(Math.multiplyExact(size, size), 3)) {
            PADDING_CHANNEL / 255f
        }
        val contentLeft = geometry.padX.toInt()
        val contentTop = geometry.padY.toInt()
        val contentRight = contentLeft + geometry.resizedWidth
        val contentBottom = contentTop + geometry.resizedHeight
        val horizontal = resizeCoefficients(bitmap.width, geometry.resizedWidth, clampEdges = true)
        val vertical = resizeCoefficients(bitmap.height, geometry.resizedHeight, clampEdges = false)
        for (y in contentTop until contentBottom) {
            val resizedY = y - contentTop
            val y0 = vertical.source[resizedY].coerceIn(0, bitmap.height - 1)
            val y1 = (vertical.source[resizedY] + 1).coerceIn(0, bitmap.height - 1)
            val beta0 = vertical.leading[resizedY]
            val beta1 = vertical.trailing[resizedY]
            for (x in contentLeft until contentRight) {
                val resizedX = x - contentLeft
                val x0 = horizontal.source[resizedX]
                val x1 = min(x0 + 1, bitmap.width - 1)
                val alpha0 = horizontal.leading[resizedX]
                val alpha1 = horizontal.trailing[resizedX]
                val topLeft = sourcePixels[y0 * bitmap.width + x0]
                val topRight = sourcePixels[y0 * bitmap.width + x1]
                val bottomLeft = sourcePixels[y1 * bitmap.width + x0]
                val bottomRight = sourcePixels[y1 * bitmap.width + x1]
                val offset = (y * size + x) * 3
                for (channel in 0..2) {
                    val shift = 16 - channel * 8
                    fun component(pixel: Int) = (pixel ushr shift) and 0xff
                    val top = component(topLeft) * alpha0 + component(topRight) * alpha1
                    val bottom = component(bottomLeft) * alpha0 + component(bottomRight) * alpha1
                    val byteValue = (
                        ((beta0 * (top shr 4)) shr 16) +
                            ((beta1 * (bottom shr 4)) shr 16) +
                            2
                    ) shr 2
                    output[offset + channel] = byteValue / 255f
                }
            }
        }
        return PreparedYoloImage(output, intArrayOf(1, size, size, 3), geometry)
    }

    private fun resizeCoefficients(
        sourceSize: Int,
        resizedSize: Int,
        clampEdges: Boolean,
    ): ResizeCoefficients {
        val source = IntArray(resizedSize)
        val leading = IntArray(resizedSize)
        val trailing = IntArray(resizedSize)
        val inverseScale = sourceSize.toDouble() / resizedSize
        for (destination in 0 until resizedSize) {
            val mapped = ((destination + 0.5) * inverseScale - 0.5).toFloat()
            var start = kotlin.math.floor(mapped).toInt()
            var fraction = mapped - start
            if (clampEdges && start < 0) {
                start = 0
                fraction = 0f
            } else if (clampEdges && start >= sourceSize - 1) {
                start = sourceSize - 1
                fraction = 0f
            }
            source[destination] = start
            leading[destination] = round((1f - fraction) * RESIZE_COEFFICIENT_SCALE).toInt()
            trailing[destination] = round(fraction * RESIZE_COEFFICIENT_SCALE).toInt()
        }
        return ResizeCoefficients(source, leading, trailing)
    }

    private data class ResizeCoefficients(
        val source: IntArray,
        val leading: IntArray,
        val trailing: IntArray,
    )
}

internal const val YOLO_INPUT_SIZE = 640
