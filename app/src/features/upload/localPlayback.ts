import type { BufferOptions } from 'expo-video';

/**
 * How much a player on a file in the phone may read ahead (the Create previews and the recorded-clip
 * review). Android's player keeps its buffer on the Java heap, and its default allows ~138 MiB per
 * player (2000 + 200 segments of 64 KiB) up to 20 s ahead, even while paused. A 4K camera clip is about
 * 40-50 Mbit/s, so two picked clips filled the app's 256 MB heap and it crashed (OutOfMemoryError in the
 * ExoPlayer threads, 2025-08-06). A file on the phone reads far faster than it plays, so a few MiB ahead
 * is enough; ten previews at most (`MAX_CLIPS_AT_ONCE`) then hold 80 MiB between them.
 */
export const LOCAL_FILE_BUFFER: BufferOptions = {
  preferredForwardBufferDuration: 5,
  minBufferForPlayback: 1,
  maxBufferBytes: 8 * 1024 * 1024,
  // Stop at the byte cap even when it holds less than the duration above (a high-bitrate clip).
  prioritizeTimeOverSizeThreshold: false,
};
