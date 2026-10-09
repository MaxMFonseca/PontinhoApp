package com.maxmfonseca.pontinho.recognition

import java.math.BigInteger
import kotlin.math.roundToInt

internal data class NativeNmsBox(val score: Float, val bounds: List<Int>) {
    init {
        require(score.isFinite() && score in 0f..1f)
        require(bounds.size == 4 && bounds.all { it in 0..320 })
        require(bounds[0] < bounds[2] && bounds[1] < bounds[3])
    }
}

/** Normalize -> integer recovery is exact for every native grid coordinate. Reject any
 * off-grid producer instead of introducing a rounding tolerance into the contract. */
internal fun nativeNmsBox(box: RawCardBox): NativeNmsBox = NativeNmsBox(box.confidence,
    listOf(box.left, box.top, box.right, box.bottom).map { value ->
        val pixel = (value * 320).roundToInt()
        require(value == pixel / 320f)
        pixel
    })

/** NMS on the native 320 grid. The verified JSON number is converted binary64 ->
 * binary32, then used as an EXACT rational; no floating-point IoU is calculated.
 * Areas <=102400, union <=204800. BigInteger products also support tiny thresholds
 * whose denominator can be 2^149, so no Long multiplication can overflow. */
internal fun nativeNms(boxes: List<NativeNmsBox>, threshold: Float): List<Int> {
    require(threshold.isFinite() && threshold > 0 && threshold <= 1)
    val bits = threshold.toRawBits()
    val exponent = (bits ushr 23) and 255
    val mantissa = (bits and 0x7fffff) or if (exponent == 0) 0 else 0x800000
    val shift = if (exponent == 0) -149 else exponent - 150
    val numerator = BigInteger.valueOf(mantissa.toLong()).shiftLeft(maxOf(shift, 0))
    val denominator = BigInteger.ONE.shiftLeft(maxOf(-shift, 0))
    fun suppress(a: NativeNmsBox, b: NativeNmsBox): Boolean {
        val x = (minOf(a.bounds[2], b.bounds[2]) - maxOf(a.bounds[0], b.bounds[0])).coerceAtLeast(0)
        val y = (minOf(a.bounds[3], b.bounds[3]) - maxOf(a.bounds[1], b.bounds[1])).coerceAtLeast(0)
        val intersection = x * y
        fun area(box: NativeNmsBox) = (box.bounds[2] - box.bounds[0]) * (box.bounds[3] - box.bounds[1])
        val union = area(a) + area(b) - intersection
        return BigInteger.valueOf(intersection.toLong()) * denominator > BigInteger.valueOf(union.toLong()) * numerator
    }
    val priority = compareByDescending<Int> { boxes[it].score }
        .thenBy { boxes[it].bounds[0] }.thenBy { boxes[it].bounds[1] }
        .thenBy { boxes[it].bounds[2] }.thenBy { boxes[it].bounds[3] }.thenBy { it }
    val kept = mutableListOf<Int>()
    boxes.indices.sortedWith(priority).forEach { i -> if (kept.none { suppress(boxes[it], boxes[i]) }) kept += i }
    return kept.sortedWith(compareBy<Int> { boxes[it].bounds[1] }.thenBy { boxes[it].bounds[0] }
        .thenBy { boxes[it].bounds[3] }.thenBy { boxes[it].bounds[2] }.thenBy { it })
}
