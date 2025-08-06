import { LOCAL_FILE_BUFFER } from './localPlayback';
import { MAX_CLIPS_AT_ONCE } from './pickVideo';

/** The Pixel's per-app Java heap limit (the OutOfMemoryError's "growth limit", 2025-08-06). */
const JAVA_HEAP = 256 * 1024 * 1024;

describe('LOCAL_FILE_BUFFER', () => {
  it('lets the most clips Create can show at once buffer in under a third of the Java heap', () => {
    const bytes = LOCAL_FILE_BUFFER.maxBufferBytes ?? 0;
    // 0 means "decide for me", which is ~138 MiB per player on Android: the bug this limit fixes.
    expect(bytes).toBeGreaterThan(0);
    expect(MAX_CLIPS_AT_ONCE * bytes).toBeLessThanOrEqual(JAVA_HEAP / 3);
  });

  it('stops at the byte limit even when that is less than the duration asked for', () => {
    expect(LOCAL_FILE_BUFFER.prioritizeTimeOverSizeThreshold).toBe(false);
  });
});
