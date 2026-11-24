#!/usr/bin/env python3
"""Draw a labelled pixel grid on a photo so coordinates can be read off it.

    python3 grid.py photo.jpg photo.grid.png [--step 100]

Labels are ORIGINAL-image pixel coordinates (x along the top and bottom edges, y along the left and right edges), so the
numbers you read go straight into the sketch spec's `calibration.refs` and `x`/`y`/`w`/`h`. Needs Pillow (`pip install pillow`).
Prints the image size; look at the output, never guess the size.
"""
import argparse

from PIL import Image, ImageDraw, ImageFont, ImageOps


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--step", type=int, default=100, help="grid spacing in original pixels (default 100)")
    args = ap.parse_args()
    img = ImageOps.exif_transpose(Image.open(args.src)).convert("RGB")  # phone photos carry a rotation tag
    w, h = img.size
    draw = ImageDraw.Draw(img, "RGBA")
    font = ImageFont.load_default(size=max(12, min(w, h) // 60))
    for x in range(0, w, args.step):
        major = x % (args.step * 5) == 0
        draw.line([(x, 0), (x, h)], fill=(255, 0, 0, 150 if major else 70), width=2 if major else 1)
        for y in (2, h - 2 - font.size):
            draw.text((x + 3, y), str(x), fill=(255, 255, 0, 255), font=font, stroke_width=2, stroke_fill=(0, 0, 0, 255))
    for y in range(0, h, args.step):
        major = y % (args.step * 5) == 0
        draw.line([(0, y), (w, y)], fill=(255, 0, 0, 150 if major else 70), width=2 if major else 1)
        for x in (2, w - 2 - font.size * 3):
            draw.text((x, y + 3), str(y), fill=(0, 255, 255, 255), font=font, stroke_width=2, stroke_fill=(0, 0, 0, 255))
    img.save(args.dst)
    print(f"{args.src}: {w} x {h} px -> {args.dst} (grid every {args.step} px)")


if __name__ == "__main__":
    main()
