package com.poolaim.overlay

import org.junit.Assert.*
import org.junit.Test

class TargetBallTrajectoryTest {
    private fun p(x: Float, y: Float) = TargetBallTrajectory.Point(x, y)
    private fun trace(start: TargetBallTrajectory.Point = p(25f, 25f),
        direction: TargetBallTrajectory.Point = p(1f, 0f), bounces: Int = 3) =
        TargetBallTrajectory.calculate(start, direction, 0f, 0f, 100f, 50f, bounces)

    @Test fun cueDirectionIsIndependentFromObjectBallDirection() {
        val cue = TargetBallTrajectory.cueAfterImpact(p(1f, 0f), p(1f, 1f))
        assertNotNull(cue)
        assertEquals(0.7071f, cue!!.x, .001f)
        assertEquals(-0.7071f, cue.y, .001f)
        assertTrue(cue.y < 0f)
    }

    @Test fun headOnOrOpposingImpactHasNoReliableCueRay() {
        assertNull(TargetBallTrajectory.cueAfterImpact(p(1f, 0f), p(1f, 0f)))
        assertNull(TargetBallTrajectory.cueAfterImpact(p(1f, 0f), p(-1f, 0f)))
        assertNull(TargetBallTrajectory.cueAfterImpact(p(0f, 0f), p(1f, 0f)))
        assertNull(TargetBallTrajectory.cueAfterImpact(p(1f, 0f), p(Float.NaN, 0f)))
    }

    @Test fun independentCueAndObjectPathsStayInsideRoi() {
        val incoming = p(1f, 0f)
        val outgoing = p(1f, 1f)
        val cue = TargetBallTrajectory.cueAfterImpact(incoming, outgoing)
        assertNotNull(cue)
        val cuePath = TargetBallTrajectory.calculate(
            p(50f, 25f), cue!!, 0f, 0f, 100f, 50f, 3)
        val objectPath = TargetBallTrajectory.calculate(
            p(50f, 25f), outgoing, 0f, 0f, 100f, 50f, 3)
        assertEquals(5, cuePath.size)
        assertEquals(5, objectPath.size)
        assertNotEquals(cuePath[1], objectPath[1])
        (cuePath + objectPath).forEach { point ->
            assertTrue(point.x in 0f..100f)
            assertTrue(point.y in 0f..50f)
        }
    }

    @Test fun nearlyStraightCollisionSuppressesUnstableCueDirection() {
        assertNull(TargetBallTrajectory.cueAfterImpact(p(1f, 0f), p(1f, .01f)))
        assertNotNull(TargetBallTrajectory.cueAfterImpact(p(1f, 0f), p(1f, .2f)))
    }

    @Test fun threeReflectionsHaveFourLegsAndFivePoints() {
        assertEquals(listOf(p(25f, 25f), p(100f, 25f), p(0f, 25f), p(100f, 25f), p(0f, 25f)), trace())
    }

    @Test fun reverseDirectionReachesOppositeRailFirst() {
        val points = trace(direction = p(-1f, 0f))
        assertEquals(5, points.size)
        assertEquals(p(0f, 25f), points[1])
        assertEquals(p(100f, 25f), points[2])
    }

    @Test fun verticalRayDoesNotDivideByZero() {
        assertEquals(listOf(p(25f, 25f), p(25f, 50f), p(25f, 0f), p(25f, 50f), p(25f, 0f)),
            trace(direction = p(0f, 1f)))
    }

    @Test fun obliqueRayReflectsBothComponentsAtExactCorner() {
        assertEquals(listOf(p(25f, 25f), p(50f, 50f), p(100f, 0f), p(50f, 50f), p(0f, 0f)),
            trace(direction = p(1f, 1f)))
    }

    @Test fun outwardStartOnRailReflectsWithoutZeroLengthSegment() {
        val points = trace(start = p(100f, 25f), direction = p(1f, 0f))
        assertEquals(5, points.size)
        assertEquals(p(0f, 25f), points[1])
        assertTrue(points.zipWithNext().all { (a, b) -> a != b })
    }

    @Test fun outwardStartInCornerReflectsBothAxes() {
        val points = trace(start = p(100f, 50f), direction = p(2f, 1f))
        assertEquals(p(0f, 0f), points[1])
        assertEquals(p(100f, 50f), points[2])
        assertTrue(points.zipWithNext().all { (a, b) -> a != b })
    }

    @Test fun bounceLimitIsClampedToThree() {
        assertEquals(2, trace(bounces = 0).size)
        assertEquals(2, trace(bounces = -10).size)
        assertEquals(5, trace(bounces = 100).size)
    }

    @Test fun nonFiniteAndInvalidGeometryIsRejected() {
        assertTrue(trace(direction = p(0f, 0f)).isEmpty())
        assertTrue(trace(start = p(-1f, 25f)).isEmpty())
        assertTrue(trace(start = p(25f, 51f)).isEmpty())
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertTrue(trace(start = p(value, 25f)).isEmpty())
            assertTrue(trace(direction = p(1f, value)).isEmpty())
            assertTrue(TargetBallTrajectory.calculate(p(25f, 25f), p(1f, 1f), 0f, 0f, value, 50f).isEmpty())
        }
        assertTrue(TargetBallTrajectory.calculate(p(25f, 25f), p(1f, 1f), 100f, 0f, 0f, 50f).isEmpty())
    }

    @Test fun radiusInsetIsRespectedByEveryRailHit() {
        val points = TargetBallTrajectory.calculate(p(25f, 25f), p(1f, .37f), 10f, 10f, 90f, 40f)
        assertEquals(5, points.size)
        points.drop(1).forEach { point ->
            assertTrue(point.x in 10f..90f && point.y in 10f..40f)
            assertTrue(point.x == 10f || point.x == 90f || point.y == 10f || point.y == 40f)
        }
    }
}
