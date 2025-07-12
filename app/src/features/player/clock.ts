/** "0:07", "1:05", "12:30": a clip's time as players show it (minutes are never padded). */
export function formatClock(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

/** The time under a finger at `x` on a bar `width` wide, clamped to the clip. */
export function seekTarget(x: number, width: number, durationMs: number): number {
  if (width <= 0 || durationMs <= 0) {
    return 0;
  }
  const share = Math.min(1, Math.max(0, x / width));
  return Math.round(share * durationMs);
}

/** How far along the clip is, 0 to 1. */
export function progressShare(positionMs: number, durationMs: number): number {
  if (durationMs <= 0) {
    return 0;
  }
  return Math.min(1, Math.max(0, positionMs / durationMs));
}
