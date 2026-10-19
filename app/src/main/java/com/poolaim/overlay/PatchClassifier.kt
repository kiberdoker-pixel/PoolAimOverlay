package com.poolaim.overlay

import kotlin.math.exp
import kotlin.math.roundToInt

/** Exact tensor contract for stage31: RGB, float32, CHW [1,3,48,48], 0..1.
 * Source crop: 84x84 around a proposal, resized to 48x48. Edge sampling clamps.
 * Pixel source is Android ARGB8888, row-major. No Android dependency.
 */
object PatchTensor {
    const val CROP = 84
    const val INPUT = 48
    fun fromArgb(argb: IntArray, width: Int, height: Int, centerX: Float, centerY: Float): FloatArray {
        require(width > 0 && height > 0 && argb.size.toLong() == width.toLong() * height)
        require(centerX.isFinite() && centerY.isFinite())
        val out = FloatArray(3 * INPUT * INPUT)
        val left = centerX - CROP / 2f
        val top = centerY - CROP / 2f
        for (y in 0 until INPUT) for (x in 0 until INPUT) {
            // OpenCV resize maps destination pixel centers to source pixel centers.
            val sx = left + (x + 0.5f) * CROP / INPUT - 0.5f
            val sy = top + (y + 0.5f) * CROP / INPUT - 0.5f
            val x0 = kotlin.math.floor(sx).toInt(); val y0 = kotlin.math.floor(sy).toInt()
            val dx = sx - x0; val dy = sy - y0
            for (c in 0..2) {
                fun sample(ix: Int, iy: Int): Float {
                    val p = argb[iy.coerceIn(0, height-1) * width + ix.coerceIn(0, width-1)]
                    return ((p ushr (16 - 8*c)) and 255).toFloat()
                }
                val v = sample(x0,y0)*(1-dx)*(1-dy) + sample(x0+1,y0)*dx*(1-dy) +
                        sample(x0,y0+1)*(1-dx)*dy + sample(x0+1,y0+1)*dx*dy
                out[c*INPUT*INPUT + y*INPUT+x] = v / 255f
            }
        }
        return out
    }
}

fun interface PatchLogitModel { fun logit(chwRgb: FloatArray): Float }

data class BallProposal(val x: Float, val y: Float, val radius: Float)
data class ScoredProposal(val proposal: BallProposal, val probability: Float)

/** Candidate classifier only: it DOES NOT locate balls or identify the cue ball. */
class PatchBallClassifier(private val model: PatchLogitModel, private val threshold: Float = 0.5f) {
    init { require(threshold in 0f..1f) }
    fun score(argb: IntArray, width: Int, height: Int, proposals: List<BallProposal>): List<ScoredProposal> =
        proposals.mapNotNull { p ->
            if (!p.x.isFinite() || !p.y.isFinite() || !p.radius.isFinite() || p.radius <= 0f ||
                p.x !in 0f..width.toFloat() || p.y !in 0f..height.toFloat()) return@mapNotNull null
            val logit = model.logit(PatchTensor.fromArgb(argb, width, height, p.x, p.y))
            if (!logit.isFinite()) return@mapNotNull null
            val prob = if (logit >= 0f) 1f/(1f+exp(-logit)) else { val e=exp(logit); e/(1f+e) }
            if (prob >= threshold) ScoredProposal(p, prob) else null
        }
}
