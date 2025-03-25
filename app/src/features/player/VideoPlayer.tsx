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
  return <LivePlayer {...props} />;
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
  onProgress,
  testID,
}: VideoPlayerProps) {
  const muted = usePlayerPrefs((state) => state.muted);
  const [firstFrame, setFirstFrame] = useState(false);
  const [failed, setFailed] = useState(false);

  // ExoPlayer reads the `.m3u8` path of the master playlist as HLS.
  const player = useVideoPlayer({ uri: hlsUrl }, (created) => {
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
    setFailed(status === 'error');
  });
  // expo-video 2.0 reports no first frame; a playing clip has one on screen, so the poster goes then.
  useEventListener(player, 'playingChange', ({ isPlaying }) => {
    if (isPlaying) {
      setFirstFrame(true);
    }
  });

  return (
    <PlayerFrame
      posterUrl={posterUrl}
      blurhash={blurhash}
      aspectRatio={aspectRatio}
      showPoster={!firstFrame}
      showSound
      failed={failed}
      onRetry={() => {
        setFailed(false);
        player.replace({ uri: hlsUrl });
        if (active) {
          player.play();
        }
      }}
      testID={testID}
    >
      <VideoView
        player={player}
        style={StyleSheet.absoluteFill}
        contentFit="cover"
        nativeControls={false}
      />
    </PlayerFrame>
  );
}
