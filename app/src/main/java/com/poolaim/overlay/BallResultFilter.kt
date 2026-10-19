package com.poolaim.overlay

/** Removes nearby duplicate hits after neural-network scoring.
 *  Does not change the trained model or its probability threshold.
 */
object BallResultFilter {
    fun deduplicate(hits: List<ScoredProposal>, radius: Float): List<ScoredProposal> {
        if (hits.isEmpty()) return emptyList()
        val minDistance = (radius * 1.5f).coerceAtLeast(1f)
        val minDistanceSq = minDistance * minDistance
        val kept = ArrayList<ScoredProposal>()
        for (hit in hits.sortedByDescending { it.probability }) {
            val x = hit.proposal.x
            val y = hit.proposal.y
            if (kept.none {
                val dx = it.proposal.x - x
                val dy = it.proposal.y - y
                dx * dx + dy * dy < minDistanceSq
            }) kept.add(hit)
        }
        return kept
    }
}
