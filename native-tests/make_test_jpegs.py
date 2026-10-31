"""Generates JPEG test inputs (incl. EXIF orientation) for codec_test."""
import math
import sys

from PIL import Image

out_dir = sys.argv[1]
w, h = 800, 600
img = Image.new("RGB", (w, h))
px = img.load()
for y in range(h):
    for x in range(w):
        px[x, y] = (
            int(128 + 100 * math.sin(x / 37.0)),
            int(255 * y / h),
            int(128 + 90 * math.cos((x + y) / 51.0)),
        )

img.save(f"{out_dir}/plain_q90.jpg", quality=90)
img.save(f"{out_dir}/q100_444.jpg", quality=100, subsampling=0)

exif = Image.Exif()
exif[0x0112] = 6  # Orientation: rotate 90 CW
exif[0x010F] = "Google"
exif[0x0110] = "Pixel 9"
img.save(f"{out_dir}/exif_orient6.jpg", quality=95, exif=exif.tobytes())
img.save(f"{out_dir}/progressive.jpg", quality=85, progressive=True)
