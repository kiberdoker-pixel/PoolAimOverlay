package com.poolaim.overlay

import kotlin.math.abs

/** Experimental CPU proposal generator, no OpenCV. Coordinates refer to ORIGINAL bitmap. */
object AutoBallProposals {
    private data class Candidate(val x: Int, val y: Int, val strength: Int)
    /** Restricted to approximate table interior. User calibration is a future requirement. */
    fun find(pixels: IntArray, width: Int, height: Int, limit: Int = 45): List<BallProposal> {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val scale = width / 3200f
        val r = (27f * scale).toInt().coerceIn(5, 40)
        val step = (r / 3).coerceAtLeast(3)
        val left = (width * .18f).toInt().coerceAtLeast(2*r)
        val right = (width * .82f).toInt().coerceAtMost(width - 2*r)
        val top = (height * .23f).toInt().coerceAtLeast(2*r)
        val bottom = (height * .82f).toInt().coerceAtMost(height - 2*r)
        val candidates = ArrayList<Candidate>()
        fun distance(a: Int, b: Int): Int =
            abs((a shr 16 and 255) - (b shr 16 and 255)) +
            abs((a shr 8 and 255) - (b shr 8 and 255)) +
            abs((a and 255) - (b and 255))
        for (y in top until bottom step step) for (x in left until right step step) {
            val c = pixels[y * width + x]
            val d = r * 2
            val ring = intArrayOf(
                pixels[y * width + x - d], pixels[y * width + x + d],
                pixels[(y - d) * width + x], pixels[(y + d) * width + x],
                pixels[(y - d) * width + x - d], pixels[(y - d) * width + x + d],
                pixels[(y + d) * width + x - d], pixels[(y + d) * width + x + d]
            )
            val diff = ring.sumOf { distance(c, it) } / ring.size
            // Filter near-uniform felt; classification will reject remaining clutter.
            if (diff >= 42) candidates.add(Candidate(x, y, diff))
        }
        candidates.sortByDescending { it.strength }
        val selected = ArrayList<Candidate>()
        val suppression = r * 2
        for (p in candidates) {
            if (selected.any { q -> (p.x-q.x)*(p.x-q.x) + (p.y-q.y)*(p.y-q.y) < suppression*suppression }) continue
            selected.add(p)
            if (selected.size == limit) break
        }
        return selected.map { BallProposal(it.x.toFloat(), it.y.toFloat(), (r).toFloat()) }
    }
}
