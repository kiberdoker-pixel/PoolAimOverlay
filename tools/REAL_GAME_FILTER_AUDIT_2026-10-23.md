# Real gameplay downstream filter audit — 2026-10-23

This is a **Python/OpenCV approximation** of the current Kotlin branch-first
recognizer, NOT Android instrumentation, and NOT an accuracy score. The frame
ROI was approximated at (0.18, 0.19, 0.83, 0.93), not recovered from the app.
Kotlin uses nearest-neighbour raster sampling while this audit uses OpenCV
resize; numbers can differ. No manual truth labels are available.

| Frame | Long | Short | Aligned | Positioned | Contrast | Colored | Target | Cross | Outcome |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 25046_020s | 62 | 112 | 4445 | 714 | 440 | 321 | 205 | 28 | ambiguous |
| 24910_030s | 11 | 38 | 238 | 32 | 18 | 8 | 6 | 0 | none |
| 24910_050s | 7 | 23 | 55 | 16 | 12 | 4 | 3 | 0 | none |
| 24910_070s | 11 | 38 | 151 | 43 | 21 | 12 | 11 | 2 | accepted (direction unverified) |
| 24910_110s | 5 | 21 | 69 | 11 | 9 | 3 | 2 | 2 | accepted (direction unverified) |
| 24910_150s | 10 | 28 | 132 | 26 | 26 | 21 | 13 | 0 | none |
| 24910_210s | 8 | 21 | 89 | 18 | 13 | 12 | 4 | 0 | none |

## Next engineering decision

**Do not globally weaken the cross-support threshold**: 25046_020s already
has 28 candidates, and the ambiguity check rejects them. Relaxing evidence
without target labels could increase false positive trajectories.

1. Add curated true/false labels for each real frame and its precise table ROI.
2. Inspect pixel positions of candidates surviving target support but failing
   cross support. The current check samples both sides *behind* the short
   outgoing segment; its geometry may miss the target ball on certain angles.
3. Run those fixtures through the actual Android `inspectBitmap` path and
   assert forward origin/direction and rejection on negatives.
4. Change one filter only when a labeled fixture demonstrates the failure.

The prior synthetic Android tests pass, but they cannot establish real-game
accuracy. These audit figures are approximate and must not be presented as
full recognizer results.
