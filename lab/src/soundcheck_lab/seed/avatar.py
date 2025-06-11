"""Abstract avatars for the personas: soft overlapping circles on a gradient, drawn from a seed.

No real faces. Pure Python (zlib + struct writes the PNG), so the same handle always
gives the same bytes and the lab needs no imaging library."""

from __future__ import annotations

import hashlib
import random
import struct
import zlib

SIZE = 256


def _png(width: int, height: int, rows: list[bytes]) -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        body = kind + data
        return (
            struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
        )

    raw = b"".join(b"\x00" + row for row in rows)  # filter type 0 on every row
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)  # 8-bit RGB
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def _hsv(h: float, s: float, v: float) -> tuple[int, int, int]:
    i = int(h * 6) % 6
    f = h * 6 - int(h * 6)
    p, q, t = v * (1 - s), v * (1 - f * s), v * (1 - (1 - f) * s)
    r, g, b = [(v, t, p), (q, v, p), (p, v, t), (p, q, v), (t, p, v), (v, p, q)][i]
    return int(r * 255), int(g * 255), int(b * 255)


def avatar_png(seed: str, size: int = SIZE) -> bytes:
    """A deterministic abstract avatar for {@code seed} (a persona's handle)."""
    rng = random.Random(hashlib.sha256(seed.encode()).digest())
    hue = rng.random()
    top = _hsv(hue, 0.55, 0.35)
    bottom = _hsv((hue + 0.12) % 1, 0.65, 0.18)
    circles = [
        (
            rng.uniform(0, size),
            rng.uniform(0, size),
            rng.uniform(size * 0.15, size * 0.45),
            _hsv(
                (hue + rng.uniform(-0.15, 0.25)) % 1, rng.uniform(0.5, 0.9), rng.uniform(0.6, 1.0)
            ),
            rng.uniform(0.35, 0.7),
        )
        for _ in range(rng.randint(3, 6))
    ]
    rows = []
    for y in range(size):
        k = y / (size - 1)
        base = [int(top[c] * (1 - k) + bottom[c] * k) for c in range(3)]
        row = bytearray()
        for x in range(size):
            r, g, b = base
            for cx, cy, radius, colour, alpha in circles:
                d2 = (x - cx) ** 2 + (y - cy) ** 2
                if d2 < radius * radius:
                    a = alpha * (1 - (d2 / (radius * radius)) ** 2)  # soft edge
                    r = int(r * (1 - a) + colour[0] * a)
                    g = int(g * (1 - a) + colour[1] * a)
                    b = int(b * (1 - a) + colour[2] * a)
            row += bytes((r, g, b))
        rows.append(bytes(row))
    return _png(size, size, rows)
