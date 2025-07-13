#!/usr/bin/env python3
"""Draw the app icons from a Soundcheck mark (the app icon set, or candidate marks to compare).

A mark is a few simple shapes (capsules, circles, rounded rectangles, polygons) in a unit square, some
of them cut out of the others, filled with the Electric theme's violet → cyan gradient. Each pixel's
coverage is computed from the shapes' signed distance (negative inside), which gives one pixel of
anti-aliasing at any size. Plain Python on purpose (zlib + struct, no imaging library), so the icons
can be redrawn anywhere and there is no artwork to license.

Usage:
  python3 ops/scripts/make_icons.py [--mark NAME] [--out app/assets]   # the app's icon set
  python3 ops/scripts/make_icons.py --candidates DIR                   # every mark's layers, to compare
"""

from __future__ import annotations

import argparse
import math
import struct
import zlib
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Protocol

RGB = tuple[int, int, int]
RGBA = tuple[int, int, int, int]
Point = tuple[float, float]

# The Electric theme (app/src/theme/tokens.ts): accent and accent2, dark and light, and the backgrounds.
VIOLET: RGB = (0x7C, 0x3A, 0xED)
CYAN: RGB = (0x22, 0xD3, 0xEE)
VIOLET_LIGHT: RGB = (0x6D, 0x28, 0xD9)
CYAN_LIGHT: RGB = (0x08, 0x91, 0xB2)
BLACK: RGB = (0x09, 0x09, 0x0B)
PAPER: RGB = (0xF7, 0xF6, 0xFB)
WHITE: RGB = (0xFF, 0xFF, 0xFF)


# ── Shapes: each knows its bounding box and its signed distance (unit-square coordinates, y down) ──


class Shape(Protocol):
    def bbox(self) -> tuple[float, float, float, float]: ...
    def dist(self, u: float, v: float) -> float: ...


@dataclass(frozen=True)
class Capsule:
    """A segment from a to b with round ends of radius r: a stroke."""

    a: Point
    b: Point
    r: float

    def bbox(self) -> tuple[float, float, float, float]:
        (ax, ay), (bx, by), r = self.a, self.b, self.r
        return (min(ax, bx) - r, min(ay, by) - r, max(ax, bx) + r, max(ay, by) + r)

    def dist(self, u: float, v: float) -> float:
        (ax, ay), (bx, by) = self.a, self.b
        pax, pay, bax, bay = u - ax, v - ay, bx - ax, by - ay
        h = min(max((pax * bax + pay * bay) / (bax * bax + bay * bay), 0.0), 1.0)
        return math.hypot(pax - bax * h, pay - bay * h) - self.r


@dataclass(frozen=True)
class Cone:
    """A capsule whose radius goes from ra at a to rb at b (Inigo Quilez's uneven capsule)."""

    a: Point
    b: Point
    ra: float
    rb: float

    def bbox(self) -> tuple[float, float, float, float]:
        (ax, ay), (bx, by), m = self.a, self.b, max(self.ra, self.rb)
        return (min(ax, bx) - m, min(ay, by) - m, max(ax, bx) + m, max(ay, by) + m)

    def dist(self, u: float, v: float) -> float:
        (ax, ay), (bx, by), ra, rb = self.a, self.b, self.ra, self.rb
        bax, bay = bx - ax, by - ay
        l2 = bax * bax + bay * bay
        rr = ra - rb
        a2 = l2 - rr * rr
        il2 = 1.0 / l2
        pax, pay = u - ax, v - ay
        y = pax * bax + pay * bay
        z = y - l2
        xx, xy = pax * l2 - bax * y, pay * l2 - bay * y
        x2 = xx * xx + xy * xy
        y2 = y * y * l2
        z2 = z * z * l2
        k = math.copysign(1.0, rr) * rr * rr * x2
        if math.copysign(1.0, z) * a2 * z2 > k:
            return math.sqrt(x2 + z2) * il2 - rb
        if math.copysign(1.0, y) * a2 * y2 < k:
            return math.sqrt(x2 + y2) * il2 - ra
        return (math.sqrt(x2 * a2 * il2) + y * rr) * il2 - ra


@dataclass(frozen=True)
class Circle:
    c: Point
    r: float

    def bbox(self) -> tuple[float, float, float, float]:
        (cx, cy), r = self.c, self.r
        return (cx - r, cy - r, cx + r, cy + r)

    def dist(self, u: float, v: float) -> float:
        return math.hypot(u - self.c[0], v - self.c[1]) - self.r


@dataclass(frozen=True)
class RoundRect:
    """A rectangle centred on c, half-size hw × hh, corners rounded by r."""

    c: Point
    hw: float
    hh: float
    r: float

    def bbox(self) -> tuple[float, float, float, float]:
        (cx, cy) = self.c
        return (cx - self.hw, cy - self.hh, cx + self.hw, cy + self.hh)

    def dist(self, u: float, v: float) -> float:
        qx = abs(u - self.c[0]) - self.hw + self.r
        qy = abs(v - self.c[1]) - self.hh + self.r
        return math.hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0) - self.r


@dataclass(frozen=True)
class Polygon:
    """Any simple polygon (Inigo Quilez's exact distance), grown by `grow` to soften its corners."""

    pts: tuple[Point, ...]
    grow: float = 0.0

    def bbox(self) -> tuple[float, float, float, float]:
        xs, ys, g = [p[0] for p in self.pts], [p[1] for p in self.pts], self.grow
        return (min(xs) - g, min(ys) - g, max(xs) + g, max(ys) + g)

    def dist(self, u: float, v: float) -> float:
        pts = self.pts
        d = (u - pts[0][0]) ** 2 + (v - pts[0][1]) ** 2
        sign = 1.0
        j = len(pts) - 1
        for i in range(len(pts)):
            ex, ey = pts[j][0] - pts[i][0], pts[j][1] - pts[i][1]
            wx, wy = u - pts[i][0], v - pts[i][1]
            h = min(max((wx * ex + wy * ey) / (ex * ex + ey * ey), 0.0), 1.0)
            d = min(d, (wx - ex * h) ** 2 + (wy - ey * h) ** 2)
            below, above, left = v >= pts[i][1], v < pts[j][1], ex * wy > ey * wx
            if (below and above and left) or (not below and not above and not left):
                sign = -sign
            j = i
        return sign * math.sqrt(d) - self.grow


# ── Marks ──────────────────────────────────────────────────────────────────────────────────────────

Fill = Callable[[float, float], RGB]


@dataclass
class Layer:
    """Shapes painted with one fill; `cuts` are taken out of them (they show what is underneath)."""

    shapes: list[Shape]
    cuts: list[Shape] = field(default_factory=list)


@dataclass
class Mark:
    """A mark: its layers, a tilt in degrees (clockwise) and a one-line idea, for the candidates sheet."""

    layers: list[Layer]
    idea: str
    tilt: float = 0.0


def gradient(c0: RGB, c1: RGB) -> Fill:
    """Violet at the top left to cyan at the bottom right of the mark's box."""

    def fill(u: float, v: float) -> RGB:
        t = min(max((u + v - 0.3) / 1.4, 0.0), 1.0)
        return (
            round(c0[0] + (c1[0] - c0[0]) * t),
            round(c0[1] + (c1[1] - c0[1]) * t),
            round(c0[2] + (c1[2] - c0[2]) * t),
        )

    return fill


def solid(colour: RGB) -> Fill:
    return lambda u, v: colour


def arc(c: Point, rho: float, a0: float, a1: float, steps: int) -> list[Point]:
    """Points on a circle of radius rho round c, from angle a0 to a1 (degrees, counter-clockwise)."""
    out = []
    for i in range(steps + 1):
        phi = math.radians(a0 + (a1 - a0) * i / steps)
        out.append((c[0] + rho * math.cos(phi), c[1] - rho * math.sin(phi)))
    return out


def stroke(points: list[Point], r: float) -> list[Shape]:
    """A round-capped stroke along a polyline: one capsule per segment (their union is seamless)."""
    return [Capsule(points[i], points[i + 1], r) for i in range(len(points) - 1)]


def mic_check() -> Mark:
    """A tick whose long arm is a microphone's handle: "mic check", the soundcheck ritual."""
    a, b = (0.06, 0.56), (0.31, 0.81)
    head, head_r = (0.69, 0.29), 0.165
    length = math.hypot(head[0] - b[0], head[1] - b[1])
    ux, uy = (head[0] - b[0]) / length, (head[1] - b[1]) / length
    # The handle widens towards the head and stops short of it, leaving a thin collar of background.
    c = (head[0] - ux * (head_r + 0.05), head[1] - uy * (head_r + 0.05))
    grille: list[Shape] = []
    for k in (-0.055, 0.0, 0.055):  # three slots across the handle's axis
        cx, cy = head[0] + ux * k, head[1] + uy * k
        half = math.sqrt(head_r**2 - k**2) - 0.05
        grille.append(Capsule((cx + uy * half, cy - ux * half), (cx - uy * half, cy + ux * half), 0.016))
    return Mark(
        [Layer([Capsule(a, b, 0.066), Cone(b, c, 0.066, 0.085)]), Layer([Circle(head, head_r)], grille)],
        idea="Mic check: a tick whose long arm is a microphone",
    )


def ticket() -> Mark:
    """A gig ticket with a play button punched through it: the gig, and the clips from it."""
    notches: list[Shape] = [Circle((0.03, 0.5), 0.075), Circle((0.97, 0.5), 0.075)]
    play = Polygon(((0.43, 0.375), (0.43, 0.625), (0.635, 0.5)), grow=0.035)
    return Mark(
        [Layer([RoundRect((0.5, 0.5), 0.47, 0.31, 0.08)], [*notches, play])],
        idea="Ticket: a gig ticket with a play button punched through",
        tilt=-12,
    )


def pin() -> Mark:
    """A map pin with three meter bars in its head: gigs near you, and their sound."""
    c, r, tip = (0.5, 0.385), 0.335, (0.5, 0.95)
    beta = math.pi / 2 - math.asin(r / (tip[1] - c[1]))  # where the pin's sides touch the head
    left = (c[0] - r * math.sin(beta), c[1] + r * math.cos(beta))
    right = (c[0] + r * math.sin(beta), c[1] + r * math.cos(beta))
    w = 0.046
    bars: list[Shape] = [
        Capsule((x, 0.51 - w), (x, 0.51 - h), w) for x, h in ((0.375, 0.12), (0.5, 0.24), (0.625, 0.17))
    ]
    return Mark(
        [Layer([Circle(c, r), Polygon((left, right, tip))], bars)],
        idea="Pin: a map pin with three meter bars in its head",
    )


def letter_s() -> Mark:
    """An S drawn as one stroke of two arcs, leaning forward."""
    rho = 0.19
    points = arc((0.5, 0.5 - rho), rho, 0, 270, 80) + arc((0.5, 0.5 + rho), rho, 90, -180, 80)[1:]
    return Mark([Layer(stroke(points, 0.072))], idea="S: one stroke of two arcs, leaning forward", tilt=-10)


MARKS: dict[str, Callable[[], Mark]] = {
    "mic-check": mic_check,
    "ticket": ticket,
    "pin": pin,
    "letter-s": letter_s,
}
DEFAULT_MARK = "mic-check"


# ── Rendering ──────────────────────────────────────────────────────────────────────────────────────


def tilted(mark: Mark) -> Callable[[float, float], Point]:
    """Maps a point of the drawing to the mark's own (untilted) frame."""
    cos_t, sin_t = math.cos(math.radians(-mark.tilt)), math.sin(math.radians(-mark.tilt))

    def to_mark(x: float, y: float) -> Point:
        dx, dy = x - 0.5, y - 0.5
        return (0.5 + dx * cos_t - dy * sin_t, 0.5 + dx * sin_t + dy * cos_t)

    return to_mark


def signed_distance(u: float, v: float, shapes: list[Shape], cuts: list[Shape]) -> float:
    d = min(s.dist(u, v) for s in shapes)
    for cut in cuts:
        d = max(d, -cut.dist(u, v))
    return d


def extent(mark: Mark) -> tuple[float, float, float]:
    """Centre (x, y) and larger side of the drawn mark, found by sampling, so every mark is centred
    and fills its box the same way whatever its shapes' coordinates and tilt."""
    to_mark = tilted(mark)
    n = 160
    xs: list[float] = []
    ys: list[float] = []
    for j in range(n):
        y = -0.25 + 1.5 * (j + 0.5) / n
        for i in range(n):
            x = -0.25 + 1.5 * (i + 0.5) / n
            u, v = to_mark(x, y)
            if any(signed_distance(u, v, layer.shapes, layer.cuts) < 0 for layer in mark.layers):
                xs.append(x)
                ys.append(y)
    return ((min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2, max(max(xs) - min(xs), max(ys) - min(ys)))


def draw(
    size: int, box: float, mark: Mark, fit: tuple[float, float, float], fill: Fill, background: RGB | None
) -> list[list[RGBA]]:
    """The mark (whose `extent` is `fit`) fitted into a `box`-pixel square in the middle of a `size` canvas."""
    bg: RGBA = (*background, 255) if background else (0, 0, 0, 0)
    rows = [[bg for _ in range(size)] for _ in range(size)]
    if box <= 0:
        return rows  # a plain background layer
    cx, cy, side = fit
    to_mark = tilted(mark)
    scale = side / box  # drawing units per pixel
    grid = 32
    for layer in mark.layers:
        # A coarse grid of the shapes and cuts near each cell, so a pixel only measures what is close.
        cells: dict[tuple[int, int], tuple[list[Shape], list[Shape]]] = {}
        margin = 2 * scale
        for kind, shapes in ((0, layer.shapes), (1, layer.cuts)):
            for s in shapes:
                x0, y0, x1, y1 = s.bbox()
                for gy in range(math.floor((y0 - margin) * grid), math.floor((y1 + margin) * grid) + 1):
                    for gx in range(math.floor((x0 - margin) * grid), math.floor((x1 + margin) * grid) + 1):
                        cells.setdefault((gx, gy), ([], []))[kind].append(s)
        for py in range(size):
            y = cy + (py + 0.5 - size / 2) * scale
            row = rows[py]
            for px in range(size):
                x = cx + (px + 0.5 - size / 2) * scale
                u, v = to_mark(x, y)
                near = cells.get((math.floor(u * grid), math.floor(v * grid)))
                if not near or not near[0]:
                    continue
                d = signed_distance(u, v, near[0], near[1])
                a = min(max(0.5 - d / scale, 0.0), 1.0)  # one pixel of anti-aliasing
                if a <= 0:
                    continue
                colour = fill((x - cx) / side + 0.5, (y - cy) / side + 0.5)
                r, g, b, alpha = row[px]
                if background:
                    row[px] = (
                        round(r + (colour[0] - r) * a),
                        round(g + (colour[1] - g) * a),
                        round(b + (colour[2] - b) * a),
                        255,
                    )
                else:
                    row[px] = (*colour, max(alpha, round(255 * a)))
    return rows


def png(rows: list[list[RGBA]]) -> bytes:
    height, width = len(rows), len(rows[0])
    raw = b"".join(b"\x00" + bytes(c for px in row for c in px) for row in rows)

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)  # 8-bit RGBA
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def icon_set(mark: Mark) -> dict[str, list[list[RGBA]]]:
    dark, light, white = gradient(VIOLET, CYAN), gradient(VIOLET_LIGHT, CYAN_LIGHT), solid(WHITE)
    fit = extent(mark)
    return {
        # Full-bleed square (iOS, web manifest, older Android): the mark at a little over half the size.
        "icon.png": draw(1024, 560, mark, fit, dark, BLACK),
        # Android adaptive icon: layers of 1024 whose middle 66 % is always visible, so the mark stays
        # inside it; the system masks the background into a circle, squircle or square.
        "android-icon-foreground.png": draw(1024, 440, mark, fit, dark, None),
        "android-icon-background.png": draw(1024, 0, mark, fit, dark, BLACK),
        "android-icon-monochrome.png": draw(1024, 440, mark, fit, white, None),
        # The splash screen (expo-splash-screen in app/app.config.ts): the mark alone on transparency,
        # in the dark theme's colours on black and the light theme's on paper.
        "splash-icon.png": draw(1024, 640, mark, fit, dark, None),
        "splash-icon-light.png": draw(1024, 640, mark, fit, light, None),
        # The in-app mark beside the name (Wordmark.tsx), dark and light.
        "mark.png": draw(256, 256, mark, fit, dark, None),
        "mark-light.png": draw(256, 256, mark, fit, light, None),
        "favicon.png": draw(48, 32, mark, fit, dark, BLACK),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mark", choices=sorted(MARKS), default=DEFAULT_MARK)
    parser.add_argument("--out", type=Path, default=Path("app/assets"))
    parser.add_argument("--candidates", type=Path, help="write every mark's icon set into DIR/<mark>/")
    args = parser.parse_args()
    targets = [(name, args.candidates / name) for name in MARKS] if args.candidates else [(args.mark, args.out)]
    for name, out in targets:
        out.mkdir(parents=True, exist_ok=True)
        for file, rows in icon_set(MARKS[name]()).items():
            (out / file).write_bytes(png(rows))
            print(f"wrote {out / file} ({len(rows)} px, {name})")


if __name__ == "__main__":
    main()
