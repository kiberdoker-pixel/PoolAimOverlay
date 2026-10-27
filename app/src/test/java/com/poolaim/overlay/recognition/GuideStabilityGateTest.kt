package com.poolaim.overlay.recognition

import org.junit.Assert.*
import org.junit.Test

class GuideStabilityGateTest {
    private val bounds = TableBounds(.1f, .1f, .9f, .9f)
    private val guide = RecognizedGuide(GuidePoint(300f, 300f), GuidePoint(350f, 300f), 10f, .95f)
    private fun frame(g: RecognizedGuide? = guide) = FrameGuideResult(1000, 600, bounds, g, "candidate")
    private fun lock(gate: GuideStabilityGate, f: FrameGuideResult = frame(), start: Long = 100L) {
        assertNull(gate.filter(f, start).guide)
        assertNull(gate.filter(f, start + 100L).guide)
        assertEquals(f.guide, gate.filter(f, start + 200L).guide)
    }

    @Test fun acceptsOnlyAfterThreeConsecutiveFrames() {
        val gate = GuideStabilityGate()
        lock(gate)
        assertEquals(guide, gate.filter(frame(), 400L).guide)
    }

    @Test fun scoreIsRankingHeuristicButMustBeFiniteAndBounded() {
        for (score in listOf(Float.NaN, Float.POSITIVE_INFINITY, 1.01f, -.01f)) {
            val gate = GuideStabilityGate()
            repeat(5) { assertNull(gate.filter(frame(guide.copy(score = score)), it * 100L).guide) }
        }
        lock(GuideStabilityGate(), frame(guide.copy(score = .50f)))
    }

    @Test fun missingOrAmbiguousObservationImmediatelyClearsConfirmedGuide() {
        val gate = GuideStabilityGate()
        lock(gate)
        val absent = frame(null).copy(reason = "Несколько равноправных линий")
        assertEquals(absent, gate.filter(absent, 400L))
        lock(gate, start = 500L)
    }

    @Test fun lowerFiniteScoreAfterLockDoesNotEraseConsistentGeometry() {
        val gate = GuideStabilityGate()
        lock(gate)
        assertNotNull(gate.filter(frame(guide.copy(score = .5f)), 400L).guide)
    }

    @Test fun reversedEndpointsStartASeparateConfirmationSequence() {
        val gate = GuideStabilityGate()
        lock(gate)
        val reversed = frame(guide.copy(origin = guide.tip, tip = guide.origin))
        lock(gate, reversed, 400L)
    }

    @Test fun movementBeyondBallRelativeToleranceRemovesLock() {
        val gate = GuideStabilityGate()
        lock(gate)
        val moved = frame(guide.copy(origin = GuidePoint(305f, 300f), tip = GuidePoint(355f, 300f)))
        assertNull(gate.filter(moved, 400L).guide)
        assertNull(gate.filter(moved, 500L).guide)
        assertEquals(moved.guide, gate.filter(moved, 600L).guide)
    }

    @Test fun smallIncrementalDriftCannotAccumulateIntoAConfirmation() {
        val gate = GuideStabilityGate()
        repeat(3) { i ->
            val offset = i * 2.5f
            val moving = frame(guide.copy(origin = GuidePoint(300f + offset, 300f),
                tip = GuidePoint(350f + offset, 300f)))
            assertNull(gate.filter(moving, 100L + i * 100L).guide)
        }
    }

    @Test fun angleChangeWithinPositionToleranceStillRemovesLock() {
        val gate = GuideStabilityGate()
        val initial = frame(guide.copy(ballRadius = 30f))
        lock(gate, initial)
        val rotated = frame(guide.copy(tip = GuidePoint(349.6f, 306.1f), ballRadius = 30f))
        assertNull(gate.filter(rotated, 400L).guide)
    }

    @Test fun changedBoundsOrRotationRequireNewConfirmation() {
        for (changed in listOf(frame().copy(bounds = bounds.copy(left = .11f)),
            frame().copy(width = 1100), frame().copy(height = 650))) {
            val gate = GuideStabilityGate()
            lock(gate)
            lock(gate, changed, 400L)
        }
    }

    @Test fun staleGapRequiresNewConfirmationAndBoundaryGapIsAllowed() {
        val gate = GuideStabilityGate()
        assertNull(gate.filter(frame(), 100L).guide)
        assertNull(gate.filter(frame(), 600L).guide)
        assertNotNull(gate.filter(frame(), 1100L).guide)
        lock(gate, start = 1601L)
    }

    @Test fun backwardsOrRepeatedTimestampClearsTheSequence() {
        for (badTime in listOf(200L, 300L, -1L)) {
            val gate = GuideStabilityGate()
            lock(gate)
            assertNull(gate.filter(frame(), badTime).guide)
            lock(gate, start = 400L)
        }
    }

    @Test fun explicitResetClearsLock() {
        val gate = GuideStabilityGate()
        lock(gate)
        gate.reset()
        lock(gate, start = 400L)
    }

    @Test fun extrapolatedTipOutsideRailDoesNotSuppressValidAim() {
        val nearRail = frame(guide.copy(
            origin = GuidePoint(875f, 300f),
            tip = GuidePoint(925f, 300f)
        ))
        lock(GuideStabilityGate(), nearRail)
    }

    @Test fun rejectsNonFiniteDegenerateAndOutOfBoundsGeometry() {
        val invalid = listOf(
            frame().copy(width = 0), frame().copy(height = -1),
            frame().copy(bounds = bounds.copy(left = Float.NaN)),
            frame().copy(bounds = bounds.copy(left = -.1f)),
            frame().copy(bounds = bounds.copy(right = .1f)),
            frame(guide.copy(origin = GuidePoint(Float.NaN, 300f))),
            frame(guide.copy(tip = GuidePoint(350f, Float.POSITIVE_INFINITY))),
            frame(guide.copy(tip = guide.origin)),
            frame(guide.copy(ballRadius = 0f)), frame(guide.copy(ballRadius = -1f)),
            frame(guide.copy(ballRadius = Float.NaN)), frame(guide.copy(ballRadius = 500f)),
            // Capture coordinates are inside the ROI, but outside the ball-center inset.
            frame(guide.copy(origin = GuidePoint(105f, 300f)))
        )
        for (bad in invalid) {
            val gate = GuideStabilityGate()
            repeat(4) { assertNull(bad.toString(), gate.filter(bad, it * 100L).guide) }
        }
    }
}
