import type { TapPoint } from './tapGesture';

export type PlaybackProgress = { positionMs: number; durationMs: number };

export type VideoPlayerProps = {
  /** The HLS master playlist. */
  hlsUrl: string;
  /**
   * Which clip this is, for sharing one player between screens (the post id). Two players with the
   * same key on Android are one native player (see `playerPool`). Defaults to the playlist URL.
   */
  clipKey?: string;
  posterUrl?: string | null;
  blurhash?: string | null;
  /** Width divided by height; clips are mostly portrait. */
  aspectRatio?: number;
  /** False shows the poster only, without creating a player (see `playerWindow`). */
  live?: boolean;
  /** True plays, false pauses. In a list exactly one clip is active. */
  active: boolean;
  loop?: boolean;
  /** False draws square corners: a clip that fills a feed page edge to edge (default true). */
  rounded?: boolean;
  /** Puts the sound button in the top corner (the feed lays its own controls over the foot of the clip). */
  soundAtTop?: boolean;
  /**
   * Android's video surface. A `SurfaceView` (the default) is cheapest but is not clipped by rounded
   * corners while its parent scrolls; the post page uses a `TextureView`. Fixed per view.
   */
  surface?: 'surfaceView' | 'textureView';
  /** A double tap on the clip (the post likes it); a single tap pauses or plays. */
  onDoubleTap?: (point: TapPoint) => void;
  /** Where to start, for a player that cannot be shared across screens (the web). */
  startAtMs?: number;
  /** Called about four times a second while playing; watch-time telemetry hooks in here. */
  onProgress?: (progress: PlaybackProgress) => void;
  testID?: string;
};
