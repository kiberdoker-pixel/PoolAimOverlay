#!/usr/bin/env python3
"""Create repeatable visual review samples from real 8 Ball Pool recordings.

Requires: pip install opencv-python pillow
Usage: python tools/video_review.py --output review short.mp4 full_match.mp4
The resulting index.csv and contact_sheet.jpg are inspection aids, not proof
that Android's OpenCV recognizer passed on-device.
"""
import argparse
import csv
import json
from pathlib import Path
import cv2
from PIL import Image, ImageDraw

def sample(video, interval, width):
    capture = cv2.VideoCapture(str(video))
    if not capture.isOpened():
        raise RuntimeError(f"Cannot open {video}")
    duration = capture.get(cv2.CAP_PROP_FRAME_COUNT) / max(1, capture.get(cv2.CAP_PROP_FPS))
    t = 0.0
    try:
        while t < duration:
            capture.set(cv2.CAP_PROP_POS_MSEC, t * 1000)
            ok, frame = capture.read()
            if ok:
                h, w = frame.shape[:2]
                frame = cv2.resize(frame, (width, round(h * width / w)))
                yield round(t, 2), Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
            t += interval
    finally:
        capture.release()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("videos", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, default=Path("video-review"))
    parser.add_argument("--interval", type=float, default=5.0)
    parser.add_argument("--width", type=int, default=640)
    parser.add_argument("--max-frames", type=int, default=0, help="0 means no limit")
    args = parser.parse_args()
    if args.interval <= 0 or args.width < 100:
        parser.error("interval must be positive and width >= 100")
    args.output.mkdir(parents=True, exist_ok=True)
    labels = {}
    label_file = Path(__file__).with_name("review_scenes.json")
    if label_file.exists():
        for scene in json.loads(label_file.read_text(encoding="utf-8")):
            labels[(scene["video"], float(scene["second"]))] = scene
    tiles, records = [], []
    counts = {"aiming": 0, "no-aim": 0, "unreviewed": 0}
    for video in args.videos:
        for second, img in sample(video, args.interval, args.width):
            filename = f"{video.stem}_{int(second * 1000):08d}.jpg"
            img.save(args.output / filename, quality=90)
            scene = labels.get((video.name, second))
            status = scene["scene"] if scene else "unreviewed"
            records.append((video.name, second, filename, status))
            counts[status] = counts.get(status, 0) + 1
            tile = img.copy()
            draw = ImageDraw.Draw(tile)
            draw.rectangle((0, 0, min(img.width, 250), 24), fill="#111111")
            draw.text((6, 5), f"{video.name} / {second:.1f}s / {status}", fill="white")
            tiles.append(tile)
            if args.max_frames > 0 and len(records) >= args.max_frames:
                break
        if args.max_frames > 0 and len(records) >= args.max_frames:
            break
    with (args.output / "index.csv").open("w", newline="", encoding="utf-8") as file:
        writer = csv.writer(file)
        writer.writerow(("video", "timestamp_seconds", "frame", "review_status"))
        writer.writerows(records)
    if tiles:
        cols = 3
        rows = (len(tiles) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * tiles[0].width, rows * tiles[0].height), "#151515")
        for i, tile in enumerate(tiles):
            sheet.paste(tile, ((i % cols) * tile.width, (i // cols) * tile.height))
        sheet.save(args.output / "contact_sheet.jpg", quality=85)
    print(f"Extracted {len(records)} frames to {args.output}; labels: {counts}")
    print("IMPORTANT: scene labels are visual review only, NOT ground-truth target coordinates.")
    print("IMPORTANT: visual review is not a native recognizer test.")

if __name__ == "__main__":
    main()
