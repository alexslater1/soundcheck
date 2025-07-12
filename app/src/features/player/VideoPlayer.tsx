import { useEventListener } from 'expo';
import { createVideoPlayer, VideoView, type VideoPlayer as NativePlayer } from 'expo-video';
import { useEffect, useLayoutEffect, useState, useSyncExternalStore } from 'react';
import { StyleSheet } from 'react-native';

import { usePlayback } from './PlaybackScope';
import { ClipPoster, PlayerFrame } from './PlayerFrame';
import { createPlayerPool } from './playerPool';
import type { PlaybackProgress, VideoPlayerProps } from './types';

const DEFAULT_ASPECT = 9 / 16;

/**
 * Every native player in the app, one per clip, shared by the screens that show it. The
 * pool releases a player when its last holder lets go: a released player is never handed to a view.
 */
const pool = createPlayerPool<NativePlayer>({
  destroy: (player) => player.release(),
  schedule: (fn, ms) => {
    const timer = setTimeout(fn, ms);
    return () => clearTimeout(timer);
  },
});

/** Players that have drawn a frame: moved to a new view, they need no poster while they play on. */
const framed = new WeakSet<NativePlayer>();

/**
 * Keeps a clip's player for the screen about to open (the post page), so that the clip plays on there
 * instead of starting again. The feed calls it as it opens a post.
 */
export function handOverPlayer(clipKey: string): boolean {
  return pool.reserve(clipKey);
}

/** Players that exist right now (tests check that none are left behind). */
export function livePlayerCount(): number {
  return pool.size();
}

function newPlayer(hlsUrl: string): NativePlayer {
  const player = createVideoPlayer({ uri: hlsUrl, contentType: 'hls' });
  player.loop = true;
  player.muted = true;
  player.timeUpdateEventInterval = 0.25;
  return player;
}

/**
 * A clip on Android: expo-video, which plays HLS natively (Media3/ExoPlayer picks the rendition that
 * fits the connection). The web twin is `VideoPlayer.web.tsx`. With `live` false only the poster is
 * rendered and no player exists.
 */
export function VideoPlayer(props: VideoPlayerProps) {
  const { live = true, posterUrl, blurhash, aspectRatio = DEFAULT_ASPECT, rounded, testID } = props;
  if (!live) {
    return (
      <PlayerFrame
        posterUrl={posterUrl}
        blurhash={blurhash}
        aspectRatio={aspectRatio}
        showPoster
        showSound={false}
        rounded={rounded}
        testID={testID}
      />
    );
  }
  return <RecoveringPlayer {...props} />;
}

/** How long to wait before the one automatic retry of a clip that failed to play. */
const AUTO_RETRY_MS = 1500;

/**
 * A live player that recovers by starting over: a retry replaces the clip's native player with a new
 * one (`pool.renew`) and the view is mounted again for it. Reusing the old one is what can fail with
 * "Cannot set prop 'player' on view SurfaceVideoView": once a player has been released (a dev reload,
 * or a network error that tore it down), the native view cannot take it again. The first failure is
 * retried by itself once; after that the viewer gets the "could not be played" note with a Retry
 * button.
 */
function RecoveringPlayer(props: VideoPlayerProps) {
  const key = props.clipKey ?? props.hlsUrl;
  const [failed, setFailed] = useState(false);
  const [autoRetried, setAutoRetried] = useState(false);

  useEffect(() => {
    if (!failed || autoRetried) {
      return;
    }
    const timer = setTimeout(() => {
      setAutoRetried(true);
      setFailed(false);
      pool.renew(key);
    }, AUTO_RETRY_MS);
    return () => clearTimeout(timer);
  }, [failed, autoRetried, key]);

  return (
    <LivePlayer
      {...props}
      clipKey={key}
      failed={failed && autoRetried}
      onFailed={() => setFailed(true)}
      onRetry={() => {
        setFailed(false);
        pool.renew(key);
      }}
    />
  );
}

/**
 * expo-video's player is a handle to a native object (ExoPlayer): its settings are changed by
 * assigning to it, not by creating a new one.
 */
function configure(player: NativePlayer, settings: { muted: boolean; loop: boolean }) {
  player.muted = settings.muted;
  player.loop = settings.loop;
}

function seekTo(player: NativePlayer, positionMs: number) {
  player.currentTime = positionMs / 1000;
}

type LiveProps = VideoPlayerProps & {
  clipKey: string;
  failed: boolean;
  onFailed: () => void;
  onRetry: () => void;
};

/**
 * Holds the clip's player from the pool. Only the holder on top controls it (plays, pauses, reports
 * progress and errors) and shows its video; a holder underneath (the feed while the post page is open)
 * shows the poster and leaves the player alone.
 */
function LivePlayer(props: LiveProps) {
  const { clipKey, hlsUrl, posterUrl, blurhash, aspectRatio = DEFAULT_ASPECT, rounded } = props;
  const [holder] = useState(pool.newHolder);

  // Before paint: the first frame on screen already has the player (or the poster for one pass).
  useLayoutEffect(() => {
    pool.acquire(clipKey, holder, () => newPlayer(hlsUrl));
    return () => pool.release(clipKey, holder);
  }, [clipKey, holder, hlsUrl]);

  const player = useSyncExternalStore(pool.subscribe, () => pool.player(clipKey));
  const generation = useSyncExternalStore(pool.subscribe, () => pool.generation(clipKey));
  const controls = useSyncExternalStore(pool.subscribe, () => pool.controls(clipKey, holder));
  const shows = useSyncExternalStore(pool.subscribe, () => pool.shows(clipKey, holder));

  if (!player) {
    return (
      <PlayerFrame
        posterUrl={posterUrl}
        blurhash={blurhash}
        aspectRatio={aspectRatio}
        showPoster
        showSound={false}
        rounded={rounded}
      />
    );
  }
  return (
    <HeldPlayer key={generation} {...props} player={player} controls={controls} shows={shows} />
  );
}

function HeldPlayer({
  player,
  controls,
  shows,
  posterUrl,
  blurhash,
  aspectRatio = DEFAULT_ASPECT,
  active,
  loop = true,
  soundAtTop,
  rounded,
  surface = 'surfaceView',
  onDoubleTap,
  onProgress,
  testID,
  failed,
  onFailed,
  onRetry,
}: LiveProps & { player: NativePlayer; controls: boolean; shows: boolean }) {
  const { onScreen, muted } = usePlayback();
  // Paused by a tap. It belongs to one viewing: a clip swiped away and back plays again.
  const [userPaused, setUserPaused] = useState(false);
  if (!active && userPaused) {
    setUserPaused(false);
  }
  // A clip plays only while it is the active one, its screen is the one in view and the
  // viewer has not paused it.
  const playing = active && onScreen && !userPaused;
  const [progress, setProgress] = useState<PlaybackProgress>({ positionMs: 0, durationMs: 0 });

  useEffect(() => {
    if (controls) {
      configure(player, { muted, loop });
    }
  }, [player, controls, muted, loop]);

  useEffect(() => {
    if (!controls) {
      return;
    }
    if (playing) {
      player.play();
    } else {
      player.pause();
    }
  }, [player, controls, playing]);

  useEventListener(player, 'timeUpdate', ({ currentTime }) => {
    if (!controls) {
      return;
    }
    const now = {
      positionMs: Math.round(currentTime * 1000),
      durationMs: Math.round(player.duration * 1000),
    };
    setProgress(now);
    onProgress?.(now);
  });
  useEventListener(player, 'statusChange', ({ status }) => {
    if (controls && status === 'error') {
      onFailed();
    }
  });

  return (
    <PlayerFrame
      posterUrl={posterUrl}
      blurhash={blurhash}
      aspectRatio={aspectRatio}
      showPoster={!shows}
      showSound
      soundAtTop={soundAtTop}
      rounded={rounded}
      failed={failed}
      onRetry={onRetry}
      paused={userPaused}
      onTogglePause={() => setUserPaused((paused) => !paused)}
      onDoubleTap={onDoubleTap}
      seek={
        progress.durationMs > 0
          ? {
              ...progress,
              onSeek: (positionMs) => {
                seekTo(player, positionMs);
                setProgress((now) => ({ ...now, positionMs }));
              },
            }
          : undefined
      }
      testID={testID}
    >
      {shows ? (
        <ShownVideo
          player={player}
          surface={surface}
          posterUrl={posterUrl}
          blurhash={blurhash}
          playing={playing}
          testID={testID}
        />
      ) : null}
    </PlayerFrame>
  );
}

/**
 * The player's picture. Mounting it attaches the player to this view (expo-video moves the picture
 * from the view that had it). Until the first frame is drawn here the poster covers it, unless the
 * player is carrying on with a clip it was already showing elsewhere (the handoff from the feed).
 */
function ShownVideo({
  player,
  surface,
  posterUrl,
  blurhash,
  playing,
  testID,
}: {
  player: NativePlayer;
  surface: 'surfaceView' | 'textureView';
  posterUrl?: string | null;
  blurhash?: string | null;
  playing: boolean;
  testID?: string;
}) {
  const [carriedOn] = useState(() => framed.has(player) && playing);
  const [firstFrame, setFirstFrame] = useState(false);
  return (
    <>
      <VideoView
        player={player}
        style={StyleSheet.absoluteFill}
        contentFit="cover"
        nativeControls={false}
        surfaceType={surface}
        onFirstFrameRender={() => {
          framed.add(player);
          setFirstFrame(true);
        }}
      />
      {firstFrame || carriedOn ? null : (
        <ClipPoster
          posterUrl={posterUrl}
          blurhash={blurhash}
          testID={testID ? `${testID}-poster` : undefined}
        />
      )}
    </>
  );
}
