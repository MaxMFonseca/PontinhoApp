package com.maxmfonseca.pontinho.recognition

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

data class ImageDecodeLimits(
    val maxWidth: Int = 8_192,
    val maxHeight: Int = 8_192,
    // At most two original ARGB buffers coexist (orientation or one crop).
    // Reserve at least half the heap for models/UI; native analysis is model-sized.
    val maxPixels: Int = minOf(16_777_216L, Runtime.getRuntime().maxMemory() / 16).coerceAtLeast(1).toInt(),
) {
    init {
        require(maxWidth > 0)
        require(maxHeight > 0)
        require(maxPixels > 0)
    }
}

data class ImageBounds(val width: Int, val height: Int)

sealed class RecognitionImageException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    class AccessDenied : RecognitionImageException("Image access was denied")

    class DecodeFailed : RecognitionImageException("Image could not be decoded")

    class InvalidDimensions :
        RecognitionImageException("Image dimensions are invalid")

    class SizeLimitExceeded(cause: Throwable? = null) :
        RecognitionImageException("Image exceeds safe decode limits", cause)
}

internal fun interface ImageStreamOpener {
    fun open(uri: Uri): InputStream
}

internal interface ImageDecoder {
    fun readBounds(stream: InputStream): ImageBounds
    fun decode(stream: InputStream, sampleSize: Int): Bitmap?
}

internal fun interface ImageOrientationReader {
    fun read(uri: Uri, stream: InputStream): ExifTransform
}

internal enum class ExifTransform(val swapsDimensions: Boolean) {
    NORMAL(false),
    FLIP_HORIZONTAL(false),
    ROTATE_180(false),
    FLIP_VERTICAL(false),
    TRANSPOSE(true),
    ROTATE_90(true),
    TRANSVERSE(true),
    ROTATE_270(true),
}

/**
 * Decodes image content selected through stable Photo Picker grants or files in app-owned cache.
 *
 * Each phase deliberately reopens the URI because provider streams need not support mark/reset. Callers that
 * accept a mutable provider should first snapshot it into app-owned cache; this loader never snapshots into an
 * unbounded byte array. Photo Picker grants and scanner cache files are stable for the duration of one load.
 */
class RecognitionImageLoader internal constructor(
    private val opener: ImageStreamOpener,
    private val decoder: ImageDecoder,
    private val orientationReader: ImageOrientationReader,
    private val limits: ImageDecodeLimits,
    private val bitmapRecycler: (Bitmap) -> Unit = Bitmap::recycle,
) {
    constructor(
        contentResolver: ContentResolver,
        limits: ImageDecodeLimits = ImageDecodeLimits(),
    ) : this(
        opener = ImageStreamOpener { uri ->
            contentResolver.openInputStream(uri) ?: throw IOException("Content stream unavailable")
        },
        decoder = BitmapFactoryImageDecoder,
        orientationReader = PlatformExifOrientationReader,
        limits = limits,
    )

    fun load(uri: Uri): Bitmap {
        val bounds = withStream(uri) { decoder.readBounds(it) }
        if (bounds.width <= 0 || bounds.height <= 0) {
            throw RecognitionImageException.InvalidDimensions()
        }

        val transform = withStream(uri) { orientationReader.read(uri, it) }
        ensureWithinLimits(bounds.width, bounds.height, transform)
        val decoded = try {
            withStream(
                uri = uri,
                discard = { bitmap -> bitmap?.let(::recycleOwned) },
            ) { decoder.decode(it, 1) }
                ?: throw RecognitionImageException.DecodeFailed()
        } catch (error: OutOfMemoryError) {
            throw RecognitionImageException.SizeLimitExceeded(error)
        }

        try {
            ensureWithinLimits(decoded.width, decoded.height, transform)
            return applyTransformAndConfig(decoded, transform)
        } catch (error: OutOfMemoryError) {
            recycleOwned(decoded)
            throw RecognitionImageException.SizeLimitExceeded(error)
        } catch (error: Throwable) {
            recycleOwned(decoded)
            throw error
        }
    }

    private inline fun <T> withStream(
        uri: Uri,
        discard: (T) -> Unit = {},
        block: (InputStream) -> T,
    ): T {
        val stream = try {
            opener.open(uri)
        } catch (error: Throwable) {
            throwOpenError(error)
        }
        val outcome = runCatching { block(stream) }
        val closeFailure = runCatching { stream.close() }.exceptionOrNull()
        val operationFailure = outcome.exceptionOrNull()
        if (operationFailure != null) {
            throwStreamError(operationFailure)
        }
        val value = outcome.getOrThrow()
        if (closeFailure != null) {
            discard(value)
            throwStreamError(closeFailure)
        }
        return value
    }

    private fun throwOpenError(error: Throwable): Nothing = when (error) {
        is CancellationException -> throw error
        is OutOfMemoryError -> throw RecognitionImageException.SizeLimitExceeded(error)
        is SecurityException, is IOException -> throw RecognitionImageException.AccessDenied()
        else -> throw error
    }

    private fun throwStreamError(error: Throwable): Nothing = when (error) {
        is CancellationException -> throw error
        is RecognitionImageException -> throw error
        is OutOfMemoryError -> throw RecognitionImageException.SizeLimitExceeded(error)
        is SecurityException -> throw RecognitionImageException.AccessDenied()
        is IOException -> throw RecognitionImageException.DecodeFailed()
        is RuntimeException -> throw RecognitionImageException.DecodeFailed()
        else -> throw error
    }

    private fun recycleOwned(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmapRecycler(bitmap)
    }

    private fun ensureWithinLimits(sourceWidth: Int, sourceHeight: Int, transform: ExifTransform) {
        val width = if (transform.swapsDimensions) sourceHeight else sourceWidth
        val height = if (transform.swapsDimensions) sourceWidth else sourceHeight
        if (
            width > limits.maxWidth ||
            height > limits.maxHeight ||
            width.toLong() * height.toLong() > limits.maxPixels.toLong()
        ) {
            throw RecognitionImageException.SizeLimitExceeded()
        }
    }

    private fun applyTransformAndConfig(source: Bitmap, transform: ExifTransform): Bitmap {
        val transformed = if (transform == ExifTransform.NORMAL) {
            source
        } else {
            transformPixels(source, transform).also { recycleOwned(source) }
        }
        if (transformed.config == Bitmap.Config.ARGB_8888) return transformed

        return transformed.copy(Bitmap.Config.ARGB_8888, false)
            ?.also { if (it !== transformed) recycleOwned(transformed) }
            ?: run {
                recycleOwned(transformed)
                throw RecognitionImageException.DecodeFailed()
            }
    }

    private fun transformPixels(source: Bitmap, transform: ExifTransform): Bitmap {
        val targetWidth = if (transform.swapsDimensions) source.height else source.width
        val targetHeight = if (transform.swapsDimensions) source.width else source.height
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        try {
            val line = IntArray(maxOf(source.width, source.height))
            for (targetY in 0 until targetHeight) {
                when (transform) {
                    ExifTransform.FLIP_HORIZONTAL,
                    ExifTransform.ROTATE_180,
                    ExifTransform.FLIP_VERTICAL,
                    -> {
                        val sourceY = when (transform) {
                            ExifTransform.FLIP_HORIZONTAL -> targetY
                            else -> source.height - 1 - targetY
                        }
                        source.getPixels(line, 0, source.width, 0, sourceY, source.width, 1)
                        if (transform != ExifTransform.FLIP_VERTICAL) line.reversePrefix(source.width)
                    }
                    ExifTransform.TRANSPOSE,
                    ExifTransform.ROTATE_90,
                    ExifTransform.TRANSVERSE,
                    ExifTransform.ROTATE_270,
                    -> {
                        val sourceX = when (transform) {
                            ExifTransform.TRANSPOSE,
                            ExifTransform.ROTATE_90,
                            -> targetY
                            else -> source.width - 1 - targetY
                        }
                        source.getPixels(line, 0, 1, sourceX, 0, 1, source.height)
                        if (transform == ExifTransform.ROTATE_90 || transform == ExifTransform.TRANSVERSE) {
                            line.reversePrefix(source.height)
                        }
                    }
                    ExifTransform.NORMAL -> error("Normal orientation does not require transformation")
                }
                output.setPixels(line, 0, targetWidth, 0, targetY, targetWidth, 1)
            }
            return output
        } catch (error: Throwable) {
            recycleOwned(output)
            throw error
        }
    }

    private fun IntArray.reversePrefix(size: Int) {
        var left = 0
        var right = size - 1
        while (left < right) {
            val value = this[left]
            this[left] = this[right]
            this[right] = value
            left++
            right--
        }
    }

}

internal object BitmapFactoryImageDecoder : ImageDecoder {
    override fun readBounds(stream: InputStream): ImageBounds {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(stream, null, options)
        return ImageBounds(options.outWidth, options.outHeight)
    }

    override fun decode(stream: InputStream, sampleSize: Int): Bitmap? = BitmapFactory.decodeStream(
        stream,
        null,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
}

internal object PlatformExifOrientationReader : ImageOrientationReader {
    override fun read(uri: Uri, stream: InputStream): ExifTransform = when (
        ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransform.FLIP_HORIZONTAL
        ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransform.ROTATE_180
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransform.FLIP_VERTICAL
        ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransform.TRANSPOSE
        ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransform.ROTATE_90
        ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransform.TRANSVERSE
        ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransform.ROTATE_270
        else -> ExifTransform.NORMAL
    }
}
