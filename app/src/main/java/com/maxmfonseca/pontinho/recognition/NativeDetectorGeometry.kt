package com.maxmfonseca.pontinho.recognition

import android.graphics.RectF

internal const val NATIVE_BOX_POLICY = "mediapipe-integer-interior-1px-v1"

/** Native has already clamped/truncated origin and extent independently. Never infer that
 * a zero origin was originally inside the image. Keep the analysis coordinate grid. */
internal fun nativeCardBox(score: Float, bounds: RectF, width: Int, height: Int) = RawCardBox(
    score, bounds.left / width, bounds.top / height, bounds.right / width, bounds.bottom / height,
)

/** One analysis-pixel guard covers the sum of two truncated native terms (right/bottom can
 * be 319 although the raw edge was >=320). Integer grid, no arbitrary float epsilon.
 * This policy is bound to selection/evaluation/release evidence, before NMS or crops. */
internal fun isCompleteNativeCard(box: RawCardBox, width: Int, height: Int): Boolean =
    box.left > 1f / width && box.top > 1f / height &&
        box.right < (width - 1f) / width && box.bottom < (height - 1f) / height &&
        box.left < box.right && box.top < box.bottom
