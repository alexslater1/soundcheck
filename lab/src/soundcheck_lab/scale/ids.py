"""UUIDv7 (RFC 9562), as the backend's `Ids.newId()` makes them. Python 3.13's `uuid` has no uuid7.

Layout: 48-bit Unix time in milliseconds, then the version (7), 12 random bits, the variant
(0b10) and 62 random bits. Ids made from later timestamps sort after earlier ones, which is what
keeps the B-tree inserts of real ids at the right-hand edge of the index.
"""

from __future__ import annotations

import uuid

import numpy as np
import numpy.typing as npt


def uuid7(unix_ms: int, rand: bytes) -> uuid.UUID:
    """One UUIDv7 from a timestamp in ms and 10 random bytes (pure: same input, same id)."""
    if not 0 <= unix_ms < 1 << 48:
        raise ValueError("unix_ms out of range")
    if len(rand) != 10:
        raise ValueError("need 10 random bytes")
    raw = bytearray(unix_ms.to_bytes(6, "big") + rand)
    raw[6] = 0x70 | (raw[6] & 0x0F)  # version 7
    raw[8] = 0x80 | (raw[8] & 0x3F)  # variant 0b10
    return uuid.UUID(bytes=bytes(raw))


def uuid7_strings(unix_ms: npt.NDArray[np.int64], rng: np.random.Generator) -> list[str]:
    """Many UUIDv7s at once, as canonical strings (the form COPY reads)."""
    n = len(unix_ms)
    raw = np.empty((n, 16), dtype=np.uint8)
    ms = unix_ms.astype(np.uint64)
    for i in range(6):
        raw[:, i] = ((ms >> np.uint64(8 * (5 - i))) & np.uint64(0xFF)).astype(np.uint8)
    raw[:, 6:] = rng.integers(0, 256, size=(n, 10), dtype=np.uint8)
    raw[:, 6] = 0x70 | (raw[:, 6] & 0x0F)
    raw[:, 8] = 0x80 | (raw[:, 8] & 0x3F)
    hexed = raw.tobytes().hex()
    out: list[str] = []
    for i in range(0, 32 * n, 32):
        h = hexed[i : i + 32]
        out.append(f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}")
    return out
