package com.poolaim.overlay.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class GuideEndpointSupportTest {
    private val width = 40
    private val height = 30

    @Test
    fun sideExitFromConnectedStripeHasForwardSupport() {
        val mask = mask {
            for (x in 3..32) for (y in 10..11) white(x, y)
        }
        assertTrue(support(mask, 15.0, 12.0, 1.0, .05, 10.0))
    }

    @Test
    fun actualCapHasNoForwardSupport() {
        val mask = mask {
            for (x in 3..15) for (y in 10..11) white(x, y)
        }
        assertFalse(support(mask, 15.0, 11.0))
    }

    @Test
    fun nearbyDisconnectedStripeDoesNotEraseActualCap() {
        val mask = mask {
            for (x in 3..15) white(x, 10)
            for (x in 13..32) white(x, 15)
        }
        assertFalse(support(mask, 15.0, 10.0, radius = 15.0))
    }

    @Test
    fun branchesConnectedOnlyAtDistantOriginRemainSeparateLocally() {
        val mask = mask {
            for (x in 3..15) white(x, 10)
            for (x in 3..32) white(x, 15)
            for (y in 10..15) white(3, y)
        }
        assertFalse(support(mask, 15.0, 10.0, radius = 15.0))
    }

    @Test
    fun ambiguousPerpendicularSeedsDoNotChooseAnotherWhiteStripe() {
        val mask = mask {
            for (x in 3..15) white(x, 10)
            for (x in 15..32) white(x, 12)
        }
        // The probes at y=10 and y=12 are white, but y=11 separates them.
        assertFalse(support(mask, 15.0, 11.0))
    }

    @Test
    fun diagonalEightConnectedStripeHasForwardSupport() {
        val mask = mask { for (coordinate in 3..25) white(coordinate, coordinate) }
        val component = 1.0 / sqrt(2.0)
        assertTrue(support(mask, 11.5, 12.5, component, component))
    }

    @Test
    fun disconnectedForwardSpeckDoesNotCountAsContinuation() {
        val mask = mask {
            for (x in 3..15) white(x, 10)
            white(20, 10)
        }
        assertFalse(support(mask, 15.0, 10.0))
    }

    @Test
    fun cropEdgeDoesNotInventSupportOutsideRoi() {
        val mask = mask { for (x in 3 until width) white(x, 10) }
        assertFalse(support(mask, (width - 1).toDouble(), 10.0))
    }

    @Test
    fun continuationShorterThanTraceGapIsInsufficient() {
        val mask = mask { for (x in 3..17) white(x, 10) }
        assertFalse(support(mask, 15.0, 10.0))
    }

    @Test
    fun invalidInputsCannotRemoveAHypothesis() {
        val mask = mask { for (x in 3..32) white(x, 10) }
        assertFalse(support(mask, Double.NaN, 10.0))
        assertFalse(support(mask, 15.0, 10.0, 0.0, 0.0))
        assertFalse(support(mask, 15.0, 10.0, radius = Double.POSITIVE_INFINITY))
        assertFalse(GuideEndpointSupport.continuesForward(ByteArray(1), width, height,
            15.0, 10.0, 1.0, 0.0, 10.0))
        assertFalse(support(ByteArray(width * height), 15.0, 10.0))
    }

    private fun support(
        mask: ByteArray,
        x: Double,
        y: Double,
        dx: Double = 1.0,
        dy: Double = 0.0,
        radius: Double = 10.0
    ) = GuideEndpointSupport.continuesForward(mask, width, height, x, y, dx, dy, radius)

    private class Pixels(val data: ByteArray, val width: Int) {
        fun white(x: Int, y: Int) { data[y * width + x] = 255.toByte() }
    }

    private fun mask(draw: Pixels.() -> Unit): ByteArray = ByteArray(width * height).also {
        Pixels(it, width).draw()
    }
}
