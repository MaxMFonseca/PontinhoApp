package com.maxmfonseca.pontinho.recognition

import kotlin.math.max
import kotlin.math.min

/**
 * Metadata thresholds are parsed as binary64. Raw model scores and their confidence
 * threshold compare in binary32 to match NumPy; IoU and geometry remain binary64.
 */
data class YoloPostprocessSettings(
    val confidenceThreshold: Double,
    val iouThreshold: Double,
    val maxCards: Int = 10,
) {
    init {
        require(confidenceThreshold.isFinite() && confidenceThreshold > 0.0 && confidenceThreshold < 1.0) {
            "confidence threshold must be finite and inside (0, 1)"
        }
        require(iouThreshold.isFinite() && iouThreshold > 0.0 && iouThreshold < 1.0) {
            "IoU threshold must be finite and inside (0, 1)"
        }
        require(maxCards > 0) { "maxCards must be positive" }
    }
}

data class RawYoloDetection(
    val classIndex: Int,
    val label: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val anchorIndex: Int,
)

internal data class YoloPostprocessDiagnostics(
    val rawCandidateCount: Int,
    val maximumScore: Float,
    val rejectedBelowConfidence: Int,
    val rejectedByClassNms: Int,
    val rejectedByPhysicalNms: Int,
    val rejectedByIndexPairing: Int,
    val rejectedIncomplete: Int,
    val finalDetections: List<RawYoloDetection>,
)

open class YoloDecodeException(message: String) : IllegalArgumentException(message)

class TooManyYoloDetections(
    val detections: List<RawYoloDetection>,
    val maximum: Int,
) : YoloDecodeException("detected ${detections.size} cards; maximum is $maximum") {
    val count: Int = detections.size
}

class IncompleteYoloDetections(
    val anchorIndexes: List<Int>,
    val detections: List<RawYoloDetection>,
) : RuntimeException("detected ${anchorIndexes.size} incomplete card candidates")

object YoloCardLabels {
    val values: List<String> = listOf(
        "10C", "10D", "10H", "10S",
        "2C", "2D", "2H", "2S",
        "3C", "3D", "3H", "3S",
        "4C", "4D", "4H", "4S",
        "5C", "5D", "5H", "5S",
        "6C", "6D", "6H", "6S",
        "7C", "7D", "7H", "7S",
        "8C", "8D", "8H", "8S",
        "9C", "9D", "9H", "9S",
        "AC", "AD", "AH", "AS",
        "JC", "JD", "JH", "JS",
        "KC", "KD", "KH", "KS",
        "QC", "QD", "QH", "QS",
    )
}

object YoloPostProcessor {
    private const val OUTPUT_CHANNELS = 56
    private const val OUTPUT_ANCHORS = 8400
    private const val PHYSICAL_DUPLICATE_IOU = 0.95
    private const val CONTAINED_DUPLICATE_MIN_COVERAGE = 0.80
    private const val CONTAINED_DUPLICATE_MAX_AREA_RATIO = 0.50
    private const val BOUNDARY_EPSILON_PIXELS = 1e-4

    fun decode(
        output: FloatArray,
        shape: IntArray,
        geometry: LetterboxGeometry,
        settings: YoloPostprocessSettings,
    ): List<RawYoloDetection> = decodeInternal(output, shape, geometry, settings) {}

    internal fun decodeWithDiagnostics(
        output: FloatArray,
        shape: IntArray,
        geometry: LetterboxGeometry,
        settings: YoloPostprocessSettings,
        onDiagnostics: (YoloPostprocessDiagnostics) -> Unit = {},
    ): List<RawYoloDetection> = decodeInternal(output, shape, geometry, settings, onDiagnostics)

    private fun decodeInternal(
        output: FloatArray,
        shape: IntArray,
        geometry: LetterboxGeometry,
        settings: YoloPostprocessSettings,
        onDiagnostics: (YoloPostprocessDiagnostics) -> Unit,
    ): List<RawYoloDetection> {
        geometry.validate(expectedInputSize = YOLO_INPUT_SIZE)
        if (!shape.contentEquals(intArrayOf(1, OUTPUT_CHANNELS, OUTPUT_ANCHORS))) {
            throw YoloDecodeException("raw YOLO output must have shape (1, 56, 8400)")
        }
        if (output.size != OUTPUT_CHANNELS * OUTPUT_ANCHORS) {
            throw YoloDecodeException("raw YOLO output length does not match shape")
        }
        if (output.any { !it.isFinite() }) {
            throw YoloDecodeException("raw YOLO output contains a non-finite value")
        }
        for (anchorIndex in 0 until OUTPUT_ANCHORS) {
            if (output[index(2, anchorIndex)] < 0f || output[index(3, anchorIndex)] < 0f) {
                throw YoloDecodeException("raw YOLO output contains a negative box extent")
            }
        }

        val confidenceThreshold = settings.confidenceThreshold.toFloat()
        var maximumScore = 0f
        val candidates = buildList {
            for (anchorIndex in 0 until OUTPUT_ANCHORS) {
                var classIndex = 0
                var confidence = output[index(4, anchorIndex)]
                validateScore(confidence)
                if (confidence > maximumScore) maximumScore = confidence
                for (candidateClass in 1 until YoloCardLabels.values.size) {
                    val score = output[index(4 + candidateClass, anchorIndex)]
                    validateScore(score)
                    if (score > maximumScore) maximumScore = score
                    if (score > confidence) {
                        classIndex = candidateClass
                        confidence = score
                    }
                }
                if (confidence <= confidenceThreshold) continue
                val normalizedWidth = output[index(2, anchorIndex)].toDouble()
                val normalizedHeight = output[index(3, anchorIndex)].toDouble()
                if (normalizedWidth <= 0.0 || normalizedHeight <= 0.0) {
                    throw YoloDecodeException("selected raw YOLO box has a non-positive extent")
                }
                val centerX = output[index(0, anchorIndex)].toDouble() * geometry.inputWidth
                val centerY = output[index(1, anchorIndex)].toDouble() * geometry.inputHeight
                val modelWidth = normalizedWidth * geometry.inputWidth
                val modelHeight = normalizedHeight * geometry.inputHeight
                val modelBox = Box(
                    left = centerX - modelWidth / 2.0,
                    top = centerY - modelHeight / 2.0,
                    right = centerX + modelWidth / 2.0,
                    bottom = centerY + modelHeight / 2.0,
                )
                add(Candidate(classIndex, confidence, modelBox, anchorIndex))
            }
        }

        val priority = candidates.sortedWith(priorityOrder)
        val classAware = nms(priority, settings.iouThreshold, classAware = true)
        val physical = suppressContainedDuplicates(
            nms(classAware, PHYSICAL_DUPLICATE_IOU, classAware = false),
        )
        val incompleteAnchorIndexes = mutableListOf<Int>()
        val complete = buildList {
            physical.forEach { candidate ->
                val sourceBox = invertCompleteBox(candidate.box, geometry)
                if (sourceBox == null) {
                    incompleteAnchorIndexes += candidate.anchorIndex
                } else {
                    add(candidate.copy(box = sourceBox))
                }
            }
        }
        val consolidated = consolidateCornerIndexes(complete)
        val ordered = consolidated.sortedWith(spatialOrder).map { candidate ->
            RawYoloDetection(
                classIndex = candidate.classIndex,
                label = YoloCardLabels.values[candidate.classIndex],
                confidence = candidate.confidence,
                left = (candidate.box.left / geometry.sourceWidth).toFloat(),
                top = (candidate.box.top / geometry.sourceHeight).toFloat(),
                right = (candidate.box.right / geometry.sourceWidth).toFloat(),
                bottom = (candidate.box.bottom / geometry.sourceHeight).toFloat(),
                anchorIndex = candidate.anchorIndex,
            )
        }
        onDiagnostics(
            YoloPostprocessDiagnostics(
                rawCandidateCount = candidates.size,
                maximumScore = maximumScore,
                rejectedBelowConfidence = OUTPUT_ANCHORS - candidates.size,
                rejectedByClassNms = candidates.size - classAware.size,
                rejectedByPhysicalNms = classAware.size - physical.size,
                rejectedByIndexPairing = complete.size - consolidated.size,
                rejectedIncomplete = incompleteAnchorIndexes.size,
                finalDetections = ordered.toList(),
            ),
        )
        if (incompleteAnchorIndexes.isNotEmpty()) {
            throw IncompleteYoloDetections(incompleteAnchorIndexes.sorted(), ordered)
        }
        if (ordered.size > settings.maxCards) throw TooManyYoloDetections(ordered, settings.maxCards)
        return ordered
    }

    private fun validateScore(score: Float) {
        if (score < 0f || score > 1f) throw YoloDecodeException("raw YOLO class score is outside [0, 1]")
    }

    private fun index(channel: Int, anchor: Int): Int = channel * OUTPUT_ANCHORS + anchor

    private fun invertCompleteBox(box: Box, geometry: LetterboxGeometry): Box? {
        val contentLeft = geometry.padX
        val contentTop = geometry.padY
        val contentRight = geometry.padX + geometry.sourceWidth * geometry.scale
        val contentBottom = geometry.padY + geometry.sourceHeight * geometry.scale
        if (
            box.left < contentLeft - BOUNDARY_EPSILON_PIXELS ||
            box.top < contentTop - BOUNDARY_EPSILON_PIXELS ||
            box.right > contentRight + BOUNDARY_EPSILON_PIXELS ||
            box.bottom > contentBottom + BOUNDARY_EPSILON_PIXELS ||
            box.right <= box.left ||
            box.bottom <= box.top
        ) {
            return null
        }
        val source = Box(
            left = (box.left - geometry.padX) / geometry.scale,
            top = (box.top - geometry.padY) / geometry.scale,
            right = (box.right - geometry.padX) / geometry.scale,
            bottom = (box.bottom - geometry.padY) / geometry.scale,
        )
        if (
            source.left < -BOUNDARY_EPSILON_PIXELS ||
            source.top < -BOUNDARY_EPSILON_PIXELS ||
            source.right > geometry.sourceWidth + BOUNDARY_EPSILON_PIXELS ||
            source.bottom > geometry.sourceHeight + BOUNDARY_EPSILON_PIXELS
        ) {
            return null
        }
        return Box(
            left = source.left.coerceIn(0.0, geometry.sourceWidth.toDouble()),
            top = source.top.coerceIn(0.0, geometry.sourceHeight.toDouble()),
            right = source.right.coerceIn(0.0, geometry.sourceWidth.toDouble()),
            bottom = source.bottom.coerceIn(0.0, geometry.sourceHeight.toDouble()),
        )
    }

    private fun nms(candidates: List<Candidate>, threshold: Double, classAware: Boolean): List<Candidate> =
        buildList {
            candidates.forEach { candidate ->
                val duplicate = any { previous ->
                    (!classAware || previous.classIndex == candidate.classIndex) &&
                        intersectionOverUnion(previous.box, candidate.box) > threshold
                }
                if (!duplicate) add(candidate)
            }
        }

    private fun suppressContainedDuplicates(candidates: List<Candidate>): List<Candidate> =
        candidates.filter { candidate ->
            candidates.none { other ->
                other !== candidate && isContainedDuplicate(candidate.box, other.box)
            }
        }

    private fun consolidateCornerIndexes(candidates: List<Candidate>): List<Candidate> =
        candidates.groupBy { it.classIndex }.values.flatMap { observations ->
            observations.take((observations.size + 1) / 2)
        }

    private fun isContainedDuplicate(inner: Box, outer: Box): Boolean {
        if (inner.area <= 0.0 || inner.area > outer.area * CONTAINED_DUPLICATE_MAX_AREA_RATIO) return false
        return intersectionArea(inner, outer) / inner.area >= CONTAINED_DUPLICATE_MIN_COVERAGE
    }

    private fun intersectionOverUnion(first: Box, second: Box): Double {
        val intersection = intersectionArea(first, second)
        val union = first.area + second.area - intersection
        return if (union > 0.0) intersection / union else 0.0
    }

    private fun intersectionArea(first: Box, second: Box): Double {
        val intersectionWidth = max(0.0, min(first.right, second.right) - max(first.left, second.left))
        val intersectionHeight = max(0.0, min(first.bottom, second.bottom) - max(first.top, second.top))
        return intersectionWidth * intersectionHeight
    }

    private data class Box(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    ) {
        val area: Double
            get() = max(0.0, right - left) * max(0.0, bottom - top)
    }

    private data class Candidate(
        val classIndex: Int,
        val confidence: Float,
        val box: Box,
        val anchorIndex: Int,
    )

    private val priorityOrder = compareByDescending<Candidate> { it.confidence }
        .thenBy { it.box.left }
        .thenBy { it.box.top }
        .thenBy { it.box.right }
        .thenBy { it.box.bottom }
        .thenBy { it.classIndex }
        .thenBy { it.anchorIndex }

    private val spatialOrder = compareBy<Candidate> { it.box.left }
        .thenBy { it.box.top }
        .thenBy { it.box.right }
        .thenBy { it.box.bottom }
        .thenBy { it.classIndex }
        .thenByDescending { it.confidence }
        .thenBy { it.anchorIndex }
}
