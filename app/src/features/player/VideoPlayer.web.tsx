// hls.js documents its default export and the statics on it (`Hls.isSupported()`, `Hls.Events`); the
// bundle also exports them by name, which these two rules would rather see, but the typings do not.
/* eslint-disable import/no-named-as-default, import/no-named-as-default-member */
import Hls from 'hls.js';
import { useEffect, useRef, useState, type CSSProperties } from 'react';

import { PlayerFrame } from './PlayerFrame';
import { usePlayerPrefs } from './playerPrefs';
import type { VideoPlayerProps } from './types';

const DEFAULT_ASPECT = 9 / 16;
const FILL: CSSProperties = {
  position: 'absolute',
  inset: 0,
  width: '100%',
  height: '100%',
  objectFit: 'cover',
};

/**
 * A clip in the browser: a plain `<video>` element. Chrome and Firefox cannot play HLS themselves, so
 * hls.js fetches the playlists and segments and feeds them to the element through Media Source
 * Extensions; Safari plays HLS natively and gets the URL directly.
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
  const video = useRef<HTMLVideoElement>(null);
  const muted = usePlayerPrefs((state) => state.muted);
  const setMuted = usePlayerPrefs((state) => state.setMuted);
  const [firstFrame, setFirstFrame] = useState(false);
  const [failed, setFailed] = useState(false);
  // Incremented by "Try again": the effect below runs again and builds a fresh hls.js instance.
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    const element = video.current;
    if (!element) {
      return;
    }
    if (Hls.isSupported()) {
      const hls = new Hls({ capLevelToPlayerSize: true });
      let recovered = false;
      hls.on(Hls.Events.ERROR, (_event, data) => {
        if (!data.fatal) {
          return;
        }
        // One attempt to recover a decoding problem; anything else fatal is shown to the viewer.
        if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !recovered) {
          recovered = true;
          hls.recoverMediaError();
          return;
        }
        setFailed(true);
      });
      hls.loadSource(hlsUrl);
      hls.attachMedia(element);
      return () => hls.destroy();
    }
    if (element.canPlayType('application/vnd.apple.mpegurl')) {
      element.src = hlsUrl;
      return () => {
        element.removeAttribute('src');
        element.load();
      };
    }
    setFailed(true);
    return undefined;
  }, [hlsUrl, generation]);

  // React does not keep the `muted` attribute in step with the property, so set the property.
  useEffect(() => {
    if (video.current) {
      video.current.muted = muted;
    }
  }, [muted]);

  useEffect(() => {
    const element = video.current;
    if (!element) {
      return;
    }
    if (!active) {
      element.pause();
      return;
    }
    element.play().catch(() => {
      // Browsers refuse to start playback with sound before the user has interacted with the page.
      // Fall back to muted, which is always allowed; the sound button turns it on with a tap.
      if (!element.muted) {
        setMuted(true);
        element.muted = true;
        element.play().catch(() => undefined);
      }
    });
  }, [active, generation, setMuted]);

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
        setFirstFrame(false);
        setGeneration((n) => n + 1);
      }}
      testID={testID}
    >
      <video
        ref={video}
        data-testid={testID ? `${testID}-video` : undefined}
        style={FILL}
        muted={muted}
        loop={loop}
        playsInline
        preload="auto"
        onLoadedData={() => setFirstFrame(true)}
        onTimeUpdate={(event) => {
          const element = event.currentTarget;
          if (Number.isFinite(element.duration)) {
            onProgress?.({
              positionMs: Math.round(element.currentTime * 1000),
              durationMs: Math.round(element.duration * 1000),
            });
          }
        }}
      />
    </PlayerFrame>
  );
}
