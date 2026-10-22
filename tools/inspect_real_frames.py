#!/usr/bin/env python3
"""Offline candidate-count diagnostic for real 8 Ball Pool frames.

This is NOT a recognizer accuracy test: it reproduces only the OpenCV
white/gray mask and Hough segment extraction. Use --roi to restrict the
capture to the manually calibrated table, and inspect results before
making any claim about outgoing guide accuracy.
"""
import argparse
import csv
from pathlib import Path

import cv2
import numpy as np


def measure(path, roi):
    frame = cv2.imread(str(path))
    if frame is None:
        raise ValueError(f"Cannot decode {path}")
    height, width = frame.shape[:2]
    left, top, right, bottom = roi
    if not (0 <= left < right <= 1 and 0 <= top < bottom <= 1):
        raise ValueError("ROI must be normalized: 0 <= left < right <= 1, likewise top/bottom")
    frame = frame[int(top * height):max(int(top * height) + 1, int(np.ceil(bottom * height))),
                  int(left * width):max(int(left * width) + 1, int(np.ceil(right * width)))]
    height, width = frame.shape[:2]
    scale = min(1.0, 720.0 / max(height, width))
    if scale < 1:
        frame = cv2.resize(frame, (round(width * scale), round(height * scale)),
                           interpolation=cv2.INTER_NEAREST)
    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    mask = cv2.inRange(hsv, (0, 0, 110), (180, 55, 255))
    short = min(mask.shape)
    lines = cv2.HoughLinesP(mask, 1, np.pi / 180, max(10, round(short * .035)),
                            minLineLength=short * .04, maxLineGap=2)
    lengths = [] if lines is None else [
        float(np.hypot(x2 - x1, y2 - y1))
        for ((x1, y1, x2, y2),) in lines
    ]
    return len(lengths), sum(x >= short * .20 for x in lengths), sum(
        short * .030 * 1.6 <= x <= short * .18 for x in lengths
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("frames", type=Path, help="Folder containing extracted PNG/JPG frames")
    parser.add_argument("--roi", type=float, nargs=4, metavar=("L", "T", "R", "B"),
                        default=(0, 0, 1, 1))
    parser.add_argument("--output", type=Path, help="Optional CSV output")
    args = parser.parse_args()
    paths = sorted(p for p in args.frames.iterdir() if p.suffix.lower() in (".png", ".jpg", ".jpeg"))
    if not paths:
        parser.error("No image frames found")
    rows = []
    for path in paths:
        total, long, short = measure(path, args.roi)
        rows.append((path.name, total, long, short))
    if args.output:
        with args.output.open("w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(("frame", "all_segments", "long_segments", "short_segments"))
            writer.writerows(rows)
    else:
        writer = csv.writer(__import__("sys").stdout)
        writer.writerow(("frame", "all_segments", "long_segments", "short_segments"))
        writer.writerows(rows)


if __name__ == "__main__":
    main()
