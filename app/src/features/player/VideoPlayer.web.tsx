// hls.js documents its default export and the statics on it (`Hls.isSupported()`, `Hls.Events`); the
// bundle also exports them by name, which these two rules would rather see, but the typings do not.
/* eslint-disable import/no-named-as-default, import/no-named-as-default-member */
import Hls from 'hls.js';
import { useEffect, useRef, useState, type CSSProperties } from 'react';

import { forgetPosition, notePosition } from './handoff';
import { PlayerFrame } from './PlayerFrame';
import { usePlayback } from './PlaybackScope';
import type { PlaybackProgress, VideoPlayerProps } from './types';

const DEFAULT_ASPECT = 9 / 16;
const FILL: CSSProperties = {
  position: 'absolute',
  inset: 0,
  width: '100%',
  height: '100%',
  objectFit: 'cover',
};

/**
 * A `<video>` element cannot move from one screen to another without stopping, so on the web each
 * screen has its own: the post page starts its own where the feed's was (`startAtMs`). Nothing is
 * shared, so there is never anything to hand over.
 */
export function handOverPlayer(_clipKey: string): boolean {
  return false;
}

export function livePlayerCount(): number {
  return 0;
}

/**
 * A clip in the browser: a plain `<video>` element. Chrome and Firefox cannot play HLS themselves, so
 * hls.js fetches the playlists and segments and feeds them to the element through Media Source
 * Extensions; Safari plays HLS natively and gets the URL directly.
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
  return <LivePlayer {...props} />;
}

function LivePlayer({
  hlsUrl,
  clipKey,
  posterUrl,
  blurhash,
  aspectRatio = DEFAULT_ASPECT,
  active,
  loop = true,
  hasAudio,
  soundAtTop,
  rounded,
  onDoubleTap,
  startAtMs,
  onProgress,
  testID,
}: VideoPlayerProps) {
  const video = useRef<HTMLVideoElement>(null);
  const key = clipKey ?? hlsUrl;
  const { onScreen, muted, setMuted } = usePlayback();
  // Paused by a click or tap. It belongs to one viewing: a clip scrolled away and back plays.
  const [userPaused, setUserPaused] = useState(false);
  if (!active && userPaused) {
    setUserPaused(false);
  }
  // A clip plays only while it is the active one, its screen is the one in view and the
  // viewer has not paused it.
  const playing = active && onScreen && !userPaused;
  const [firstFrame, setFirstFrame] = useState(false);
  const [progress, setProgress] = useState<PlaybackProgress>({ positionMs: 0, durationMs: 0 });
  // The handed-over start applies once, to the first load.
  const startAt = useRef(startAtMs ?? 0);
  useEffect(() => () => forgetPosition(key), [key]);
  const [failed, setFailed] = useState(false);
  // Incremented by "Try again": the effect below runs again and builds a fresh hls.js instance.
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    const element = video.current;
    if (!element) {
      return;
    }
    if (Hls.isSupported()) {
      // hls.js assumes 500 kbit/s until it has measured a segment, so a clip opened with the default
      // starts on the 360p rung, and a 10-second clip is mostly over before it climbs. Assume a
      // normal broadband/4G speed instead: it starts on the best rung that fits the player and still
      // steps down after the first segment if the connection is slower.
      const hls = new Hls({ capLevelToPlayerSize: true, abrEwmaDefaultEstimate: 4_000_000 });
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
    if (!playing) {
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
  }, [playing, generation, setMuted]);

  return (
    <PlayerFrame
      posterUrl={posterUrl}
      blurhash={blurhash}
      aspectRatio={aspectRatio}
      showPoster={!firstFrame}
      showSound
      noSound={hasAudio === false}
      soundAtTop={soundAtTop}
      rounded={rounded}
      paused={userPaused}
      onTogglePause={() => setUserPaused((paused) => !paused)}
      onDoubleTap={onDoubleTap}
      seek={
        progress.durationMs > 0
          ? {
              ...progress,
              onSeek: (positionMs) => {
                if (video.current) {
                  video.current.currentTime = positionMs / 1000;
                }
                setProgress((now) => ({ ...now, positionMs }));
              },
            }
          : undefined
      }
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
        onLoadedMetadata={(event) => {
          if (startAt.current > 0) {
            event.currentTarget.currentTime = startAt.current / 1000;
            startAt.current = 0;
          }
        }}
        onLoadedData={() => setFirstFrame(true)}
        onTimeUpdate={(event) => {
          const element = event.currentTarget;
          if (Number.isFinite(element.duration)) {
            const now = {
              positionMs: Math.round(element.currentTime * 1000),
              durationMs: Math.round(element.duration * 1000),
            };
            setProgress(now);
            notePosition(key, now.positionMs);
            onProgress?.(now);
          }
        }}
      />
    </PlayerFrame>
  );
}
