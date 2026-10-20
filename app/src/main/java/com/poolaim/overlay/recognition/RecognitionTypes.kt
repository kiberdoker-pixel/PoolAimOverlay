package com.poolaim.overlay.recognition

/** Coordinates are full capture pixels; bounds are normalized capture coordinates. */
data class GuidePoint(val x: Float, val y: Float)

data class TableBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun isValid(): Boolean = listOf(left, top, right, bottom).all { it.isFinite() } &&
        left >= 0f && top >= 0f && right <= 1f && bottom <= 1f &&
        right - left >= 0.1f && bottom - top >= 0.1f
}

/** score is a conservative heuristic evidence score, NOT a calibrated probability. */
data class RecognizedGuide(
    val origin: GuidePoint,
    val tip: GuidePoint,
    val ballRadius: Float,
    val score: Float
)

data class FrameGuideResult(
    val width: Int,
    val height: Int,
    val bounds: TableBounds,
    val guide: RecognizedGuide? = null,
    val reason: String = "Линия прицельного шара не подтверждена"
)
