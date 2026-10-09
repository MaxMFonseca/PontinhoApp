package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.maxmfonseca.pontinho.domain.recognition.NormalizedRect
import kotlin.math.ceil
import kotlin.math.floor

internal object CardCropper {
    /** Returns a new owned bitmap even when the requested crop is the entire source. */
    fun crop(source: Bitmap, bounds: NormalizedRect, margin: Float): Bitmap {
        require(!source.isRecycled && margin.isFinite() && margin in 0f..0.25f)
        val x0 = bounds.left * source.width
        val y0 = bounds.top * source.height
        val x1 = bounds.right * source.width
        val y1 = bounds.bottom * source.height
        val mx = (x1 - x0) * margin
        val my = (y1 - y0) * margin
        val pixels = Rect(
            floor(x0 - mx).toInt().coerceAtLeast(0),
            floor(y0 - my).toInt().coerceAtLeast(0),
            ceil(x1 + mx).toInt().coerceAtMost(source.width),
            ceil(y1 + my).toInt().coerceAtMost(source.height),
        )
        val crop = Bitmap.createBitmap(pixels.width(), pixels.height(), Bitmap.Config.ARGB_8888)
        try {
            Canvas(crop).drawBitmap(source, pixels, Rect(0, 0, crop.width, crop.height), null)
            return crop
        } catch (failure: Throwable) {
            crop.recycle()
            throw failure
        }
    }
}
