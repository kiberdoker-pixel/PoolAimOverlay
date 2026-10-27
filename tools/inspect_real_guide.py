#!/usr/bin/env python3
"""Inspect Hough guide fragmentation on a real gameplay recording.

This is a diagnostic, not a recognizer accuracy test: counts cannot prove
that a candidate is the outgoing object-ball guide. Requires opencv-python.
Example:
  python tools/inspect_real_guide.py capture.mp4 --times 8 15 22 30
The ROI defaults approximate the October 22 table (not the user-selected ROI) and can be overridden.
"""
import argparse
from pathlib import Path
import cv2
import numpy as np


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("video")
    parser.add_argument("--times", type=float, nargs="+", default=[25, 35, 45, 55, 60])
    parser.add_argument("--roi", type=float, nargs=4, default=[.18, .19, .83, .93],
                        metavar=("LEFT", "TOP", "RIGHT", "BOTTOM"))
    parser.add_argument("--output-dir", type=Path, help="Save per-frame masks and Hough segments for visual inspection")
    args = parser.parse_args()
    if args.output_dir:
        args.output_dir.mkdir(parents=True, exist_ok=True)
    left, top, right, bottom = args.roi
    if not (0 <= left < right <= 1 and 0 <= top < bottom <= 1):
        parser.error("ROI must be normalized and nonempty")
    capture = cv2.VideoCapture(args.video)
    if not capture.isOpened():
        parser.error("Could not open video")
    print("second,gray_floor,max_gap,mask_percent,segments,long,short")
    for second in args.times:
        capture.set(cv2.CAP_PROP_POS_MSEC, second * 1000)
        ok, frame = capture.read()
        if not ok:
            print(f"# Unable to read frame at {second}s")
            continue
        h, w = frame.shape[:2]
        crop = frame[int(top*h):int(bottom*h), int(left*w):int(right*w)]
        scale = min(1.0, 720.0/max(crop.shape[:2]))
        crop = cv2.resize(crop, None, fx=scale, fy=scale)
        hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
        side = min(crop.shape[:2])
        for floor in (85, 110, 145):
            mask = cv2.inRange(hsv, (0, 0, floor), (180, 55, 255))
            for gap in (2.0, max(3.0, side*.025)):
                lines = cv2.HoughLinesP(mask, 1, np.pi/180,
                    max(10, round(side*.035)), minLineLength=side*.04,
                    maxLineGap=gap)
                if args.output_dir:
                    preview = crop.copy()
                    if lines is not None:
                        for line in lines[:, 0, :]:
                            x1, y1, x2, y2 = map(int, line)
                            length = np.hypot(x2-x1, y2-y1)
                            color = (0, 0, 255) if length >= side*.12 else (0, 255, 255)
                            cv2.line(preview, (x1, y1), (x2, y2), color, 1)
                    tag = f"t{second:g}_v{floor}_gap{gap:.1f}".replace(".", "p")
                    cv2.imwrite(str(args.output_dir / f"{tag}_segments.png"), preview)
                    cv2.imwrite(str(args.output_dir / f"{tag}_mask.png"), mask)
                lengths = [] if lines is None else [
                    np.hypot(x[2]-x[0], x[3]-x[1]) for x in lines[:, 0, :]
                ]
                long_count = sum(n >= side*.12 for n in lengths)
                short_count = sum(side*.030*1.6 <= n <= side*.18 for n in lengths)
                print(f"{second:g},{floor},{gap:.1f},"
                      f"{100*np.count_nonzero(mask)/mask.size:.1f},"
                      f"{len(lengths)},{long_count},{short_count}")
    capture.release()


if __name__ == "__main__":
    main()
