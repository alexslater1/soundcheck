"""Prepares a real clip for upload: the chosen part, at most 1280 px on the long side, ~5 Mbit/s
H.264.

The pipeline's best rendition is 720p (1280 px, 3 Mbit/s), so this keeps everything a
viewer will ever see while encoding about twice as fast as the app's own 1920 px / 8 Mbit/s and
halving what the local bucket stores. Metadata (GPS, recording time) is dropped from the copy.

FFmpeg runs memory-capped and at the lowest priority, and waits while the laptop is short of
memory: the machine froze once on 2025-06-11 when three other jobs and a 30 GB copy competed for 15
GB."""

from __future__ import annotations

import shutil
import subprocess
import time
from collections.abc import Callable
from pathlib import Path

MIN_AVAILABLE_MB = 1500
MEMORY_HIGH = "768M"
MEMORY_MAX = "1G"
THREADS = 4


def encode_command(
    src: Path,
    dst: Path,
    start_s: float,
    length_s: float,
    *,
    max_side: int = 1280,
    bitrate: str = "5M",
    threads: int = THREADS,
) -> list[str]:
    # the long side to at most max_side, the other side to keep the shape (-2: an even number)
    longest = f"min({max_side},iw)", f"min({max_side},ih)"
    scale = f"scale=w='if(gte(iw,ih),{longest[0]},-2)':h='if(gte(iw,ih),-2,{longest[1]})'"
    return [
        *["ffmpeg", "-hide_banner", "-loglevel", "error", "-y"],
        *["-ss", f"{start_s:.2f}", "-i", str(src), "-t", f"{length_s:.2f}"],  # the part to keep
        *["-map", "0:v:0", "-map", "0:a:0?"],  # first video, first audio if any
        *["-vf", f"{scale},format=yuv420p"],
        *["-c:v", "libx264", "-preset", "veryfast", "-b:v", bitrate, "-maxrate", bitrate],
        *["-bufsize", "10M", "-c:a", "aac", "-b:a", "160k", "-ac", "2"],
        *["-map_metadata", "-1", "-movflags", "+faststart"],  # no GPS or dates; plays while loading
        *["-threads", str(threads), str(dst)],
    ]


def available_mb() -> int:
    with open("/proc/meminfo", encoding="ascii") as fh:
        for line in fh:
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) // 1024
    return 1 << 30


def capped(command: list[str]) -> list[str]:
    """The command inside a memory-limited systemd scope at nice 19 (plain nice where systemd is
    absent)."""
    if shutil.which("systemd-run"):
        return [
            "systemd-run",
            "--user",
            "--scope",
            "--quiet",
            "-p",
            f"MemoryHigh={MEMORY_HIGH}",
            "-p",
            f"MemoryMax={MEMORY_MAX}",
            "nice",
            "-n",
            "19",
            *command,
        ]
    return ["nice", "-n", "19", *command]


def wait_for_memory(
    log: Callable[[str], None],
    sleep: Callable[[float], None] = time.sleep,
    minimum_mb: int = MIN_AVAILABLE_MB,
) -> None:
    announced = False
    while available_mb() < minimum_mb:
        if not announced:
            log(f"waiting: only {available_mb()} MB of memory available (< {minimum_mb})")
            announced = True
        sleep(15)


def encode(
    src: Path, dst: Path, start_s: float, length_s: float, log: Callable[[str], None]
) -> Path:
    """Encodes once: an existing output is reused (a re-run does not redo work)."""
    if dst.exists() and dst.stat().st_size > 0:
        return dst
    dst.parent.mkdir(parents=True, exist_ok=True)
    wait_for_memory(log)
    partial = dst.with_suffix(".part.mp4")
    subprocess.run(capped(encode_command(src, partial, start_s, length_s)), check=True)
    partial.rename(dst)
    return dst
