#!/usr/bin/env python3
"""Draw the app icons from the Soundcheck mark.

The mark is the one the app draws in `Wordmark.tsx`: five level-meter bars, bottom-aligned, heights
0.45 / 0.85 / 0.6 / 1 / 0.7 of the mark, each bar a seventh of the mark wide with half a bar between
them (so the mark is square), alternating violet (accent) and cyan (accent2) of the Electric theme, on
its true-black background. Plain Python on purpose (zlib + struct, no imaging library): every pixel's
coverage of the rounded bars is computed from a signed distance, which gives one pixel of anti-aliasing.

Usage: python3 ops/scripts/make_icons.py [app/assets]
"""

from __future__ import annotations

import struct
import sys
import zlib
from pathlib import Path

BARS = [0.45, 0.85, 0.6, 1.0, 0.7]  # Wordmark.tsx BARS
VIOLET = (0x7C, 0x3A, 0xED)  # theme accent (dark)
CYAN = (0x22, 0xD3, 0xEE)  # theme accent2 (dark)
BLACK = (0x09, 0x09, 0x0B)  # theme background (dark)
WHITE = (0xFF, 0xFF, 0xFF)

RGBA = tuple[int, int, int, int]


def bars(size: int, mark: float, colours: list[tuple[int, int, int]]) -> list[tuple[float, float, float, float, tuple[int, int, int]]]:
    """The bars of a mark `mark` pixels tall, centred in a `size` canvas: (x0, y0, x1, y1, colour)."""
    bar = mark / 7
    gap = bar / 2
    left = (size - mark) / 2
    bottom = (size + mark) / 2
    out = []
    for i, height in enumerate(BARS):
        x0 = left + i * (bar + gap)
        out.append((x0, bottom - mark * height, x0 + bar, bottom, colours[i % len(colours)]))
    return out


def coverage(px: float, py: float, x0: float, y0: float, x1: float, y1: float) -> float:
    """How much of the pixel centred at (px, py) a pill (fully rounded rectangle) covers, 0..1."""
    r = (x1 - x0) / 2
    cx = min(max(px, x0 + r), x1 - r)
    cy = min(max(py, y0 + r), y1 - r)
    d = ((px - cx) ** 2 + (py - cy) ** 2) ** 0.5 - r  # signed distance to the pill's edge
    return min(max(0.5 - d, 0.0), 1.0)


def draw(size: int, mark: float, colours: list[tuple[int, int, int]], background: RGBA | None) -> list[list[RGBA]]:
    bg: RGBA = background if background else (0, 0, 0, 0)
    rows = [[bg for _ in range(size)] for _ in range(size)]
    if mark <= 0:
        return rows  # a plain background layer
    for x0, y0, x1, y1, colour in bars(size, mark, colours):
        for y in range(max(0, int(y0) - 1), min(size, int(y1) + 2)):
            row = rows[y]
            for x in range(max(0, int(x0) - 1), min(size, int(x1) + 2)):
                a = coverage(x + 0.5, y + 0.5, x0, y0, x1, y1)
                if a <= 0:
                    continue
                r, g, b, alpha = row[x]
                if background:
                    row[x] = (
                        round(r + (colour[0] - r) * a),
                        round(g + (colour[1] - g) * a),
                        round(b + (colour[2] - b) * a),
                        255,
                    )
                else:
                    row[x] = (colour[0], colour[1], colour[2], max(alpha, round(255 * a)))
    return rows


def png(rows: list[list[RGBA]]) -> bytes:
    height, width = len(rows), len(rows[0])
    raw = b"".join(b"\x00" + bytes(c for px in row for c in px) for row in rows)

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)  # 8-bit RGBA
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def main() -> None:
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "app/assets")
    opaque: RGBA = (*BLACK, 255)
    accents = [VIOLET, CYAN]
    files = {
        # Full-bleed square (iOS, web manifest, older Android): the mark at a little over half the size.
        "icon.png": draw(1024, 540, accents, opaque),
        # Android adaptive icon: layers of 1024 whose middle 66 % is always visible, so the mark stays
        # inside it; the system masks the background into a circle, squircle or square.
        "android-icon-foreground.png": draw(1024, 420, accents, None),
        "android-icon-background.png": draw(1024, 0, accents, opaque),
        "android-icon-monochrome.png": draw(1024, 420, [WHITE], None),
        # The splash screen (expo-splash-screen in app/app.config.ts): the mark alone on transparency.
        "splash-icon.png": draw(1024, 600, accents, None),
        "favicon.png": draw(48, 30, accents, opaque),
    }
    for name, rows in files.items():
        (out / name).write_bytes(png(rows))
        print(f"wrote {out / name} ({len(rows)} px)")


if __name__ == "__main__":
    main()
