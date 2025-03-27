#!/usr/bin/env python3
"""Generate the synthetic test clips: `make clips` -> fixtures/clips/*.mp4.

Everything is original material made by FFmpeg's own signal generators (test patterns, a fractal, a
game of life, colour bars) with a tone or noise for sound and a drawn label, so the clips can be used
freely in tests, demo seeding and load tests (no copyrighted media).

The set is varied on purpose, because each variation is a code path in the pipeline:
landscape / portrait / square, 480p to 1080p (the ladder never upscales), 24 to 60 fps, 8 to 30 s,
two clips without an audio track (the worker adds silence) and two that are stored sideways with a
rotation flag, the way phones record portrait video.

Deterministic: the same script and the same ffmpeg build produce byte-identical files (fixed seeds,
`bitexact` flags so no timestamps or encoder version strings are written, a fixed thread count because
x264's decisions depend on it). `--check` regenerates the set into a temporary directory and compares
checksums with the files in place. A different ffmpeg version may encode differently; the checksums
are therefore not committed. (FFmpeg's `gradients` source is not used: in 6.1.1 it produces different
frames on every run even with a fixed seed, which the check caught.)

Usage (from the repository root):
    python3 ops/scripts/make_clips.py            # generate fixtures/clips/ and SHA256SUMS
    python3 ops/scripts/make_clips.py --check    # prove the files in place can be reproduced
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "clips"


@dataclass(frozen=True)
class Clip:
    number: int
    source: str  # lavfi video source, without size/rate/duration
    width: int
    height: int
    fps: int
    seconds: int
    audio: str | None  # lavfi audio source without duration, or None for a silent file
    rotate: int = 0  # display rotation flag written into the container
    effect: str = ""  # an extra filter applied before the label (colour cycling, negative)
    label: str = ""

    @property
    def name(self) -> str:
        shape = "square" if self.width == self.height else "landscape" if self.width > self.height else "portrait"
        if self.rotate:
            shape = "rotated"
        return f"clip-{self.number:02d}-{shape}-{min(self.width, self.height)}p-{self.seconds}s.mp4"


TONE = "sine=frequency={f}:sample_rate=48000"
NOISE = "anoisesrc=color=pink:seed={s}:sample_rate=48000:amplitude=0.2"

# fmt: off
CLIPS = [
    Clip( 1, "testsrc2",                          1280,  720, 30, 10, TONE.format(f=440)),
    Clip( 2, "testsrc2",                           720, 1280, 30, 12, TONE.format(f=523)),
    Clip( 3, "testsrc2",                          1080, 1080, 30,  8, TONE.format(f=659)),
    Clip( 4, "mandelbrot=maxiter=200",            1920, 1080, 30, 15, NOISE.format(s=4)),
    Clip( 5, "mandelbrot=maxiter=120",            1080, 1920, 30, 20, TONE.format(f=392)),
    Clip( 6, "life=seed=6:mold=10:ratio=0.35:life_color=#FF4D6D:death_color=#3DDC97",
                                                   854,  480, 30,  9, TONE.format(f=330)),
    Clip( 7, "life=seed=7:mold=20:ratio=0.3:life_color=#FFC857",
                                                   480,  854, 30, 14, NOISE.format(s=7)),
    Clip( 8, "smptehdbars",                       1280,  720, 25, 30, TONE.format(f=1000)),
    Clip( 9, "rgbtestsrc",                         720,  720, 30, 11, None),
    Clip(10, "testsrc",                           1920, 1080, 60,  8, TONE.format(f=880)),
    Clip(11, "testsrc2",                           960,  540, 24, 18, TONE.format(f=294)),
    Clip(12, "mandelbrot=maxiter=80:start_scale=2", 540,  960, 30, 25, TONE.format(f=349)),
    Clip(13, "pal100bars",                        1280,  720, 30, 16, NOISE.format(s=13), effect="hue=H=2*PI*t/8"),
    Clip(14, "testsrc2",                           720, 1280, 30, 22, TONE.format(f=247), effect="negate"),
    Clip(15, "testsrc2",                          1920, 1080, 30, 12, TONE.format(f=466), rotate=90),
    Clip(16, "smptebars",                         1280,  720, 30, 10, TONE.format(f=587), rotate=270),
    Clip(17, "life=seed=17:mold=5:ratio=0.5",      640,  480, 30, 13, None),
    Clip(18, "yuvtestsrc",                         480,  480, 30,  8, TONE.format(f=698)),
    Clip(19, "mandelbrot=maxiter=300:end_scale=0.05", 1280, 720, 30, 28, NOISE.format(s=19)),
    Clip(20, "testsrc2",                          1080, 1920, 30, 30, TONE.format(f=415)),
]
# fmt: on


def font() -> str:
    """A font file for the label; drawtext needs one and its path is part of the input."""
    try:
        path = subprocess.run(
            ["fc-match", "-f", "%{file}", "DejaVu Sans:bold"], capture_output=True, text=True, check=True
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        path = ""
    if not path or not Path(path).exists():
        sys.exit("make clips: no font found (install fonts-dejavu-core); drawtext needs one")
    return path


def command(clip: Clip, target: Path, fontfile: str) -> list[str]:
    size = f"{clip.width}x{clip.height}"
    video = clip.source + (":" if "=" in clip.source else "=") + f"size={size}:rate={clip.fps}"
    # The length is an input option (-t): not every generator has a duration parameter of its own.
    length = ["-t", str(clip.seconds)]
    text = f"Soundcheck synthetic clip {clip.number:02d}  {size}  {clip.fps} fps"
    label = (
        f"drawtext=fontfile={fontfile}:text='{text}':fontcolor=white:fontsize={max(18, clip.width // 32)}"
        ":box=1:boxcolor=black@0.6:boxborderw=8:x=(w-text_w)/2:y=h-text_h-40,"
        f"drawtext=fontfile={fontfile}:text='%{{pts\\:hms}}':fontcolor=white:fontsize={max(18, clip.width // 32)}"
        ":box=1:boxcolor=black@0.6:boxborderw=8:x=(w-text_w)/2:y=40,format=yuv420p"
    )
    args = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-f", "lavfi", *length, "-i", video]
    if clip.audio:
        args += ["-f", "lavfi", *length, "-i", clip.audio]
    args += ["-vf", f"{clip.effect},{label}" if clip.effect else label, "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-g", str(clip.fps * 2)]
    # x264 makes different decisions with a different number of threads: fix it.
    args += ["-threads", "2"]
    if clip.audio:
        args += ["-c:a", "aac", "-b:a", "128k", "-ac", "2", "-shortest"]
    # No creation time, no encoder version strings: nothing in the file depends on when it was made.
    args += ["-map_metadata", "-1", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-flags:a", "+bitexact"]
    args += ["-movflags", "+faststart", "-f", "mp4", str(target)]
    return args


def add_rotation(encoded: Path, target: Path, degrees: int) -> None:
    """What a phone does: the frames stay as recorded and a flag (a display matrix) says how to turn
    them. The stream is copied, not re-encoded; only the container gains the flag."""
    subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y"]
        + ["-display_rotation:v:0", str(degrees), "-i", str(encoded), "-c", "copy"]
        + ["-map_metadata", "-1", "-fflags", "+bitexact", "-movflags", "+faststart", "-f", "mp4", str(target)],
        check=True,
    )


def generate(directory: Path) -> dict[str, str]:
    directory.mkdir(parents=True, exist_ok=True)
    fontfile = font()
    sums: dict[str, str] = {}
    for clip in CLIPS:
        target = directory / clip.name
        if clip.rotate:
            upright = directory / f".upright-{clip.number:02d}.mp4"
            subprocess.run(command(clip, upright, fontfile), check=True)
            add_rotation(upright, target, clip.rotate)
            upright.unlink()
        else:
            subprocess.run(command(clip, target, fontfile), check=True)
        sums[clip.name] = hashlib.sha256(target.read_bytes()).hexdigest()
        print(f"  {clip.name}  {target.stat().st_size / 1_048_576:5.1f} MB")
    return sums


def write_sums(directory: Path, sums: dict[str, str]) -> None:
    lines = [f"{digest}  {name}" for name, digest in sorted(sums.items())]
    (directory / "SHA256SUMS").write_text("\n".join(lines) + "\n")


def read_sums(directory: Path) -> dict[str, str]:
    path = directory / "SHA256SUMS"
    if not path.exists():
        sys.exit("make clips-check: fixtures/clips/SHA256SUMS not found; run `make clips` first")
    return {name: digest for digest, name in (line.split("  ", 1) for line in path.read_text().splitlines())}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="regenerate into a temp dir and compare checksums")
    options = parser.parse_args()
    if shutil.which("ffmpeg") is None:
        sys.exit("make clips: ffmpeg is not on the PATH (see docs/SETUP.md)")

    if options.check:
        expected = read_sums(OUT)
        on_disk = {name: hashlib.sha256((OUT / name).read_bytes()).hexdigest() for name in expected}
        with tempfile.TemporaryDirectory(prefix="soundcheck-clips-") as temp:
            again = generate(Path(temp))
        different = sorted(name for name in again if again[name] != expected.get(name) or on_disk.get(name) != again[name])
        missing = sorted(set(expected) ^ set(again))
        if different or missing:
            sys.exit(f"make clips-check: NOT reproducible: differ={different} missing={missing}")
        print(f"make clips-check: {len(again)} clips regenerated, all byte-identical to fixtures/clips/")
        return

    for stale in OUT.glob("clip-*.mp4"):
        stale.unlink()
    sums = generate(OUT)
    write_sums(OUT, sums)
    total = sum((OUT / name).stat().st_size for name in sums) / 1_048_576
    print(f"make clips: {len(sums)} clips, {total:.1f} MB in fixtures/clips/ (checksums in SHA256SUMS)")


if __name__ == "__main__":
    main()
