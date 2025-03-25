export type PlaybackProgress = { positionMs: number; durationMs: number };

export type VideoPlayerProps = {
  /** The HLS master playlist. */
  hlsUrl: string;
  posterUrl?: string | null;
  blurhash?: string | null;
  /** Width divided by height; clips are mostly portrait. */
  aspectRatio?: number;
  /** False shows the poster only, without creating a player (see `playerWindow`). */
  live?: boolean;
  /** True plays, false pauses. In a list exactly one clip is active. */
  active: boolean;
  loop?: boolean;
  /** Called about twice a second while playing; watch-time telemetry hooks in here. */
  onProgress?: (progress: PlaybackProgress) => void;
  testID?: string;
};
