package com.poolaim.overlay

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Experimental circular candidate generator. No extra Android dependencies.
 * Checks contrast in several radial directions rather than just one high-contrast pixel.
 * This reduces (but cannot eliminate) responses to thin straight cue/guideline segments.
 */
object AutoBallProposals {
    private data class Candidate(val x: Int, val y: Int, val score: Int)
    private val dx = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    private val dy = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

    private fun diff(a: Int, b: Int): Int =
        abs((a ushr 16 and 255) - (b ushr 16 and 255)) +
        abs((a ushr 8 and 255) - (b ushr 8 and 255)) +
        abs((a and 255) - (b and 255))

    fun find(pixels: IntArray, width: Int, height: Int, limit: Int = 90): List<BallProposal> {
        require(width > 0 && height > 0 && pixels.size == width * height)
        if (width < 80 || height < 80 || limit <= 0) return emptyList()
        val scale = width / 3200f
        val radius = (27f * scale).toInt().coerceIn(5, 40)
        val outer = radius * 2
        val step = max(3, radius / 3)
        val left = max((width * .18f).toInt(), outer + 2)
        val right = min((width * .82f).toInt(), width - outer - 2)
        val top = max((height * .23f).toInt(), outer + 2)
        val bottom = min((height * .82f).toInt(), height - outer - 2)
        if (right <= left || bottom <= top) return emptyList()
        val candidates = ArrayList<Candidate>()
        for (y in top until bottom step step) for (x in left until right step step) {
            val center = pixels[y * width + x]
            var edges = 0
            var score = 0
            var maxContrast = 0
            for (i in 0 until 8) {
                val edge = pixels[(y + dy[i] * outer) * width + x + dx[i] * outer]
                val contrast = diff(center, edge)
                if (contrast >= 44) edges++
                score += min(contrast, 220)
                maxContrast = max(maxContrast, contrast)
            }
            // Straight narrow strokes generally differ from their surroundings in
            // fewer radial directions than approximately circular colored regions.
            if (edges >= 5 && score >= 420 && maxContrast >= 65) {
                candidates.add(Candidate(x, y, score))
            }
        }
        candidates.sortWith(compareByDescending<Candidate> { it.score }.thenBy { it.y }.thenBy { it.x })
        val selected = ArrayList<Candidate>()
        val suppression = radius * 2
        val suppressionSq = suppression * suppression
        for (candidate in candidates) {
            if (selected.any { old ->
                val ax = candidate.x - old.x
                val ay = candidate.y - old.y
                ax * ax + ay * ay < suppressionSq
            }) continue
            selected.add(candidate)
            if (selected.size >= limit) break
        }
        return selected.map { BallProposal(it.x.toFloat(), it.y.toFloat(), radius.toFloat()) }
    }
}
