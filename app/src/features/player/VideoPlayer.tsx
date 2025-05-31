import { useEventListener } from 'expo';
import { useVideoPlayer, VideoView, type VideoPlayer as NativePlayer } from 'expo-video';
import { useEffect, useState } from 'react';
import { StyleSheet } from 'react-native';

import { PlayerFrame } from './PlayerFrame';
import { usePlayerPrefs } from './playerPrefs';
import type { VideoPlayerProps } from './types';

const DEFAULT_ASPECT = 9 / 16;

/**
 * A clip on Android: expo-video, which plays HLS natively (Media3/ExoPlayer picks the rendition that
 * fits the connection). The web twin is `VideoPlayer.web.tsx`. With `live` false only the poster is
 * rendered and no player exists.
 */
export function VideoPlayer(props: VideoPlayerProps) {
  const { live = true, posterUrl, blurhash, aspectRatio = DEFAULT_ASPECT, testID } = props;
  if (!live) {
    return (
      <PlayerFrame
        posterUrl={posterUrl}
        blurhash={blurhash}
        aspectRatio={aspectRatio}
        showPoster
        showSound={false}
        testID={testID}
      />
    );
  }
  return <RecoveringPlayer {...props} />;
}

/** How long to wait before the one automatic retry of a clip that failed to play. */
const AUTO_RETRY_MS = 1500;

/**
 * A live player that recovers by starting over: a retry mounts a new `LivePlayer`, so expo-video builds
 * a new native player. Reusing the old one is what can fail with "Cannot set prop 'player' on view
 * SurfaceVideoView": once a player has been released (a dev reload, or a network error that tore it
 * down), the native view cannot take it again. The first failure is retried by itself once; after
 * that the viewer gets the "could not be played" note with a Retry button.
 */
function RecoveringPlayer(props: VideoPlayerProps) {
  const [attempt, setAttempt] = useState(0);
  const [failed, setFailed] = useState(false);
  const [autoRetried, setAutoRetried] = useState(false);

  useEffect(() => {
    if (!failed || autoRetried) {
      return;
    }
    const timer = setTimeout(() => {
      setAutoRetried(true);
      setFailed(false);
      setAttempt((n) => n + 1);
    }, AUTO_RETRY_MS);
    return () => clearTimeout(timer);
  }, [failed, autoRetried]);

  return (
    <LivePlayer
      key={attempt}
      {...props}
      failed={failed && autoRetried}
      onFailed={() => setFailed(true)}
      onRetry={() => {
        setFailed(false);
        setAttempt((n) => n + 1);
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

function LivePlayer({
  hlsUrl,
  posterUrl,
  blurhash,
  aspectRatio = DEFAULT_ASPECT,
  active,
  loop = true,
  soundAtTop,
  onProgress,
  testID,
  failed,
  onFailed,
  onRetry,
}: VideoPlayerProps & { failed: boolean; onFailed: () => void; onRetry: () => void }) {
  const muted = usePlayerPrefs((state) => state.muted);
  const [firstFrame, setFirstFrame] = useState(false);

  const player = useVideoPlayer({ uri: hlsUrl, contentType: 'hls' }, (created) => {
    created.loop = loop;
    created.muted = muted;
    created.timeUpdateEventInterval = 0.5;
  });

  useEffect(() => {
    configure(player, { muted, loop });
  }, [player, muted, loop]);

  useEffect(() => {
    if (active) {
      player.play();
    } else {
      player.pause();
    }
  }, [player, active]);

  useEventListener(player, 'timeUpdate', ({ currentTime }) => {
    onProgress?.({
      positionMs: Math.round(currentTime * 1000),
      durationMs: Math.round(player.duration * 1000),
    });
  });
  useEventListener(player, 'statusChange', ({ status }) => {
    if (status === 'error') {
      onFailed();
    }
  });

  return (
    <PlayerFrame
      posterUrl={posterUrl}
      blurhash={blurhash}
      aspectRatio={aspectRatio}
      showPoster={!firstFrame}
      showSound
      soundAtTop={soundAtTop}
      failed={failed}
      onRetry={onRetry}
      testID={testID}
    >
      <VideoView
        player={player}
        style={StyleSheet.absoluteFill}
        contentFit="cover"
        nativeControls={false}
        onFirstFrameRender={() => setFirstFrame(true)}
      />
    </PlayerFrame>
  );
}
