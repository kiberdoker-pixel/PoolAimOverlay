#!/usr/bin/env python3
"""Compare exported recognizer predictions against manually verified scene labels.

Predictions CSV: video,timestamp_seconds,detected,origin_x,origin_y,tip_x,tip_y
Truth CSV: video,timestamp_seconds,expected,origin_x,origin_y,angle_degrees
expected is 'none' or 'guide'. Coordinates are in original capture pixels.
A guide row needs independently reviewed origin and direction, not inferred
from recognizer output. This script deliberately does not fabricate labels.
"""
import argparse
import csv
import math
from pathlib import Path

def read_csv(path):
    with Path(path).open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))

def evaluate(truth, predictions, max_origin_error, max_angle_error):
    lookup = {(r["video"], r["timestamp_seconds"]): r for r in predictions}
    truth_keys = [(r["video"], r["timestamp_seconds"]) for r in truth]
    if len(set(truth_keys)) != len(truth_keys):
        raise ValueError("Duplicate ground-truth frame keys")
    if len(lookup) != len(predictions):
        raise ValueError("Duplicate prediction frame keys")
    total, passed, failures = 0, 0, []
    if not truth:
        raise ValueError("Empty ground truth: refusing to report a pass")
    for row in truth:
        key = (row["video"], row["timestamp_seconds"])
        if key not in lookup:
            failures.append((key, "missing prediction"))
            total += 1
            continue
        p = lookup[key]
        expected = row["expected"].strip().lower()
        detected = p["detected"].strip().lower() in ("true", "1", "yes")
        total += 1
        if expected == "none":
            ok = not detected
            reason = "false positive" if not ok else ""
        elif expected == "guide":
            if not detected:
                ok, reason = False, "missed guide"
            else:
                dx = float(p["tip_x"]) - float(p["origin_x"])
                dy = float(p["tip_y"]) - float(p["origin_y"])
                if math.hypot(dx, dy) < 1e-6:
                    ok, reason = False, "zero direction"
                else:
                    error = math.hypot(float(p["origin_x"]) - float(row["origin_x"]),
                                       float(p["origin_y"]) - float(row["origin_y"]))
                    angle = math.degrees(math.atan2(dy, dx))
                    delta = abs((angle - float(row["angle_degrees"]) + 180) % 360 - 180)
                    ok = error <= max_origin_error and delta <= max_angle_error
                    reason = f"origin error {error:.1f}px; direction error {delta:.1f}deg"
        else:
            raise ValueError(f"Invalid expected value {expected!r} at {key}")
        if ok:
            passed += 1
        else:
            failures.append((key, reason))
    for key in lookup.keys() - set(truth_keys):
        failures.append((key, "unannotated prediction frame"))
    return total, passed, failures

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--truth", required=True, type=Path)
    ap.add_argument("--predictions", required=True, type=Path)
    ap.add_argument("--origin-tolerance-px", type=float, default=12)
    ap.add_argument("--angle-tolerance-deg", type=float, default=8)
    args = ap.parse_args()
    if args.origin_tolerance_px < 0 or args.angle_tolerance_deg < 0:
        ap.error("tolerances must be nonnegative")
    total, passed, failures = evaluate(read_csv(args.truth), read_csv(args.predictions),
                                       args.origin_tolerance_px, args.angle_tolerance_deg)
    for key, reason in failures:
        print(f"FAIL {key}: {reason}")
    print(f"PASS {passed}/{total} annotated frames")
    if total == 0 or failures:
        raise SystemExit(1)

if __name__ == "__main__":
    main()
