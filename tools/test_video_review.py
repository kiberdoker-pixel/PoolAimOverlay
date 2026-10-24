#!/usr/bin/env python3
"""Offline smoke test for video_review.py, no real-match recordings required."""
import csv
import subprocess
import sys
import tempfile
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parent

def main():
    with tempfile.TemporaryDirectory() as temp:
        base = Path(temp)
        video = base / "synthetic.mp4"
        writer = cv2.VideoWriter(str(video), cv2.VideoWriter_fourcc(*"mp4v"), 5, (320, 180))
        assert writer.isOpened(), "Video codec unavailable"
        for i in range(25):
            frame = np.zeros((180, 320, 3), dtype=np.uint8)
            cv2.line(frame, (10, 90), (300, 90), (255, 255, 255), 2)
            cv2.circle(frame, (40 + i * 5, 90), 8, (0, 0, 255), -1)
            writer.write(frame)
        writer.release()
        out = base / "review"
        subprocess.run([sys.executable, str(ROOT / "video_review.py"), "--output",
                        str(out), "--interval", "1", str(video)], check=True)
        with (out / "index.csv").open(newline="", encoding="utf-8") as file:
            rows = list(csv.DictReader(file))
        assert len(rows) == 5, f"Expected 5 samples, got {len(rows)}"
        assert [float(r["timestamp_seconds"]) for r in rows] == [0., 1., 2., 3., 4.]
        assert all((out / r["frame"]).is_file() for r in rows)
        assert (out / "contact_sheet.jpg").is_file()
        print("PASS: 5 timestamped frames, index.csv, contact sheet")

if __name__ == "__main__":
    main()
