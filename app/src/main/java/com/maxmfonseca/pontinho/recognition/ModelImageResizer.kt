package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap

/** Version 1: point bilinear, half-pixel centers, clamp-to-edge, RGB8 opaque only,
 * no antialias, one final round-half-up. Integer rationals avoid platform/library
 * rounding and downsampling-kernel differences. See shared model-resize-golden.json.
 * Only model-size output and two source rows are allocated; source is borrowed. */
internal object ModelImageResizer {
    fun resize(source: Bitmap, width: Int, height: Int): Bitmap {
        require(!source.isRecycled && width in 1..320 && height in 1..320)
        require(source.width in 1..8192 && source.height in 1..8192)
        val top = IntArray(source.width)
        val bottom = IntArray(source.width)
        // Reject alpha everywhere, even pixels not sampled. Premultiplied Android
        // storage cannot preserve arbitrary straight RGBA byte values identically.
        if (source.hasAlpha()) for (y in 0 until source.height) {
            source.getPixels(top, 0, source.width, 0, y, source.width, 1)
            require(top.all { it ushr 24 == 255 }) { "Model input must be opaque" }
        }
        val dx = 2L * width
        val dy = 2L * height
        val denominator = dx * dy
        val xs = LongArray(width) { x -> ((2L * x + 1) * source.width - width).coerceIn(0, (source.width - 1) * dx) }
        val output = IntArray(width * height)
        for (y in 0 until height) {
            val ny = ((2L * y + 1) * source.height - height).coerceIn(0, (source.height - 1) * dy)
            val y0 = (ny / dy).toInt()
            val wy = ny % dy
            source.getPixels(top, 0, source.width, 0, y0, source.width, 1)
            source.getPixels(bottom, 0, source.width, 0, minOf(y0 + 1, source.height - 1), source.width, 1)
            for (x in 0 until width) {
                val x0 = (xs[x] / dx).toInt()
                val x1 = minOf(x0 + 1, source.width - 1)
                val wx = xs[x] % dx
                var color = 0xff000000.toInt()
                for (channel in 0..2) {
                    val shift = 16 - 8 * channel
                    val sum = ((top[x0] ushr shift) and 255) * (dx - wx) * (dy - wy) +
                        ((top[x1] ushr shift) and 255) * wx * (dy - wy) +
                        ((bottom[x0] ushr shift) and 255) * (dx - wx) * wy +
                        ((bottom[x1] ushr shift) and 255) * wx * wy
                    color = color or (((sum + denominator / 2) / denominator).toInt() shl shift)
                }
                output[y * width + x] = color
            }
        }
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }
}
