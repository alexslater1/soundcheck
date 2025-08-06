import { act, render, screen } from '@testing-library/react-native';

import { fireEvent } from '@/test/fireEvent';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { PlaybackScope } from './PlaybackScope';
import { usePlayerPrefs } from './playerPrefs';
import { DOUBLE_TAP_MS } from './tapGesture';
import { handOverPlayer, livePlayerCount, VideoPlayer } from './VideoPlayer';

type Listener = (payload: Record<string, unknown>) => void;

/** Calls every listener a component registered for `event` on the fake player. */
function emit(player: FakePlayer, event: string, payload: Record<string, unknown>) {
  player.listeners[event]?.forEach((listener) => listener(payload));
}

type FakePlayer = {
  loop: boolean;
  muted: boolean;
  duration: number;
  currentTime: number;
  timeUpdateEventInterval: number;
  play: jest.Mock;
  pause: jest.Mock;
  release: jest.Mock;
  replaceAsync: jest.Mock;
  listeners: Record<string, Listener[]>;
  source: unknown;
  /** Every value assigned to `muted`, in order (expo-video's native setter races on a quick flip). */
  mutedWrites: boolean[];
};

// The native module is not there under Jest: a fake player records what the component asks of it.
const mockPlayers: FakePlayer[] = [];

jest.mock('expo-video', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { View } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    createVideoPlayer: (source: unknown) => {
      const player: FakePlayer = {
        loop: false,
        muted: false,
        duration: 12.5,
        currentTime: 0,
        timeUpdateEventInterval: 0,
        play: jest.fn(),
        pause: jest.fn(),
        release: jest.fn(),
        replaceAsync: jest.fn(() => Promise.resolve()),
        listeners: {},
        source,
        mutedWrites: [],
      };
      let muted = false;
      Object.defineProperty(player, 'muted', {
        get: () => muted,
        set: (value: boolean) => {
          player.mutedWrites.push(value);
          muted = value;
        },
      });
      mockPlayers.push(player);
      return player;
    },
    VideoView: (props: { onFirstFrameRender?: () => void }) =>
      React.createElement(View, { testID: 'video-view', ...props }),
  };
});

// Whether the screen around a PlaybackScope is the one in view (react-navigation's focus).
let mockFocused = true;
jest.mock('@react-navigation/native', () => ({ useIsFocused: () => mockFocused }));

// Icon fonts are loaded through expo-asset, which is not available under Jest.
jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

jest.mock('expo', () => ({
  ...jest.requireActual<typeof import('expo')>('expo'),
  // One listener per component and event: the latest render's replaces the one before.
  useEventListener: (player: FakePlayer, event: string, listener: Listener) => {
    const React = jest.requireActual<typeof import('react')>('react');
    const latest = React.useRef(listener);
    latest.current = listener;
    React.useEffect(() => {
      const call: Listener = (payload) => latest.current(payload);
      player.listeners[event] = [...(player.listeners[event] ?? []), call];
      return () => {
        player.listeners[event] = (player.listeners[event] ?? []).filter((l) => l !== call);
      };
    }, [player, event]);
  },
}));

function renderPlayer(props: Partial<React.ComponentProps<typeof VideoPlayer>> = {}) {
  return render(
    <ThemeProvider>
      <VideoPlayer
        testID="player"
        hlsUrl="http://localhost:8088/media/abc/v1/master.m3u8"
        posterUrl="http://localhost:8088/media/abc/v1/poster.jpg"
        blurhash="LKTLyT|{fQ|{|{oLfQoLfQfQfQfQ"
        active
        {...props}
      />
    </ThemeProvider>,
  );
}

describe('VideoPlayer (native)', () => {
  beforeEach(() => {
    mockPlayers.length = 0;
    mockFocused = true;
    usePlayerPrefs.setState({ muted: true });
  });

  it('creates an HLS player that loops, starts muted and plays while active', async () => {
    await renderPlayer();

    expect(mockPlayers).toHaveLength(1);
    const player = mockPlayers[0]!;
    expect(player.source).toEqual({
      uri: 'http://localhost:8088/media/abc/v1/master.m3u8',
      contentType: 'hls',
    });
    expect(player.loop).toBe(true);
    expect(player.muted).toBe(true);
    expect(player.play).toHaveBeenCalledTimes(1);
    expect(player.pause).not.toHaveBeenCalled();
  });

  it('creates the player with the sound setting, never muting it first when sound is on', async () => {
    // expo-video 57 on Android: muted = true then false before the main thread applies the first
    // write makes the player keep volume 0 for good (its onVolumeChanged stores 0 as the user's
    // volume), so the clip plays silently while the button says sound is on.
    usePlayerPrefs.setState({ muted: false });
    await renderPlayer();

    const player = mockPlayers[0]!;
    expect(player.muted).toBe(false);
    expect(player.mutedWrites).not.toContain(true);
    expect(player.play).toHaveBeenCalledTimes(1);
  });

  it('pauses when it stops being the active clip and resumes when it is again', async () => {
    const view = await renderPlayer({ active: false });
    const player = mockPlayers[0]!;
    expect(player.play).not.toHaveBeenCalled();
    expect(player.pause).toHaveBeenCalledTimes(1);

    await view.rerender(
      <ThemeProvider>
        <VideoPlayer
          testID="player"
          hlsUrl="http://localhost:8088/media/abc/v1/master.m3u8"
          active
        />
      </ThemeProvider>,
    );
    expect(player.play).toHaveBeenCalledTimes(1);
  });

  it('shows only the poster and creates no player when it is not live', async () => {
    await renderPlayer({ live: false });

    expect(mockPlayers).toHaveLength(0);
    expect(screen.queryByTestId('video-view')).toBeNull();
    expect(screen.getByTestId('player-poster')).toBeTruthy();
    expect(screen.queryByTestId('player-sound')).toBeNull();
  });

  it('covers the video with the poster until the first frame is rendered', async () => {
    await renderPlayer();
    expect(screen.getByTestId('player-poster')).toBeTruthy();

    await act(async () => {
      (
        screen.getByTestId('video-view').props as { onFirstFrameRender: () => void }
      ).onFirstFrameRender();
    });

    expect(screen.queryByTestId('player-poster')).toBeNull();
  });

  it('toggles sound with one tap outside a screen scope, and labels the control', async () => {
    await renderPlayer();
    const player = mockPlayers[0]!;
    const sound = screen.getByLabelText('Turn sound on');

    await fireEvent.press(sound);

    expect(usePlayerPrefs.getState().muted).toBe(false);
    expect(player.muted).toBe(false);
    expect(screen.getByLabelText('Turn sound off')).toBeTruthy();
  });

  it('reports progress in milliseconds', async () => {
    const onProgress = jest.fn();
    await renderPlayer({ onProgress });

    await act(async () => emit(mockPlayers[0]!, 'timeUpdate', { currentTime: 3.25 }));

    expect(onProgress).toHaveBeenCalledWith({ positionMs: 3250, durationMs: 12_500 });
  });

  it('retries a failed clip once by itself with a new player, then offers Try again', async () => {
    jest.useFakeTimers();
    try {
      await renderPlayer();
      const first = mockPlayers[0]!;

      // First failure: no error shown; a moment later a fresh player is built (the old one may have
      // been released, and a released player cannot be handed to the native view again).
      await act(async () => emit(first, 'statusChange', { status: 'error' }));
      expect(screen.queryByTestId('player-error')).toBeNull();
      await act(async () => {
        jest.advanceTimersByTime(1500);
      });
      expect(mockPlayers).toHaveLength(2);
      expect(first.replaceAsync).not.toHaveBeenCalled();
      expect(first.release).toHaveBeenCalledTimes(1);

      // Second failure: the viewer is told, and Try again builds another new player.
      await act(async () => emit(mockPlayers[1]!, 'statusChange', { status: 'error' }));
      expect(screen.getByTestId('player-error')).toBeTruthy();
      await fireEvent.press(screen.getByText('Try again'));

      expect(mockPlayers).toHaveLength(3);
      expect(mockPlayers[2]!.source).toEqual({
        uri: 'http://localhost:8088/media/abc/v1/master.m3u8',
        contentType: 'hls',
      });
      expect(screen.queryByTestId('player-error')).toBeNull();
    } finally {
      jest.useRealTimers();
    }
  });

  it('pauses when its screen is covered or another tab is open, and resumes when it is back', async () => {
    const scoped = () => (
      <ThemeProvider>
        <PlaybackScope>
          <VideoPlayer testID="player" hlsUrl="http://localhost:8088/m/master.m3u8" active />
        </PlaybackScope>
      </ThemeProvider>
    );
    const view = await render(scoped());
    const player = mockPlayers[0]!;
    expect(player.play).toHaveBeenCalledTimes(1);

    mockFocused = false;
    await view.rerender(scoped());
    expect(player.pause).toHaveBeenCalledTimes(1);

    mockFocused = true;
    await view.rerender(scoped());
    expect(player.play).toHaveBeenCalledTimes(2);
  });

  it('shares one sound setting between screens: unmuting in one unmutes every player', async () => {
    await render(
      <ThemeProvider>
        <PlaybackScope>
          <VideoPlayer testID="feed" hlsUrl="http://localhost:8088/a/master.m3u8" active />
        </PlaybackScope>
        <PlaybackScope>
          <VideoPlayer testID="post" hlsUrl="http://localhost:8088/b/master.m3u8" active />
        </PlaybackScope>
      </ThemeProvider>,
    );
    const [feed, post] = mockPlayers;

    await fireEvent.press(screen.getByTestId('post-sound'));

    expect(post!.muted).toBe(false);
    expect(feed!.muted).toBe(false);
    expect(usePlayerPrefs.getState().muted).toBe(false);
  });

  it('starts unmuted on a first launch, and remembers the choice on the device', () => {
    expect(usePlayerPrefs.getInitialState().muted).toBe(false);
    usePlayerPrefs.getState().setMuted(true);
    expect(usePlayerPrefs.persist.getOptions().partialize?.(usePlayerPrefs.getState())).toEqual({
      muted: true,
    });
  });

  it('shows "No sound" instead of a toggle for a clip with no audio track', async () => {
    await render(
      <ThemeProvider>
        <VideoPlayer
          testID="player"
          hlsUrl="http://localhost:8088/media/abc/v1/master.m3u8"
          active
          hasAudio={false}
        />
      </ThemeProvider>,
    );
    expect(screen.getByTestId('player-no-sound')).toBeTruthy();
    expect(screen.getByLabelText('No sound')).toBeTruthy();
    expect(screen.queryByLabelText('Turn sound on')).toBeNull();
  });

  it('keeps the toggle when the clip has audio or it is not known', async () => {
    await renderPlayer();
    expect(screen.queryByTestId('player-no-sound')).toBeNull();
    expect(screen.getByLabelText('Turn sound on')).toBeTruthy();
  });

  it('pauses on a tap with a play sign, plays on the next, and plays again after a swipe away', async () => {
    const view = await renderPlayer();
    const player = mockPlayers[0]!;
    expect(screen.queryByTestId('player-paused')).toBeNull();

    await fireEvent.press(screen.getByLabelText('Pause'));
    expect(player.pause).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('player-paused')).toBeTruthy();
    // The tap pauses; it does not touch the sound.
    expect(player.muted).toBe(true);

    await fireEvent.press(screen.getByLabelText('Play'));
    expect(player.play).toHaveBeenCalledTimes(2);

    // Paused, then swiped away and back: a new viewing, so it plays.
    await fireEvent.press(screen.getByLabelText('Pause'));
    const at = (active: boolean) => (
      <ThemeProvider>
        <VideoPlayer
          testID="player"
          hlsUrl="http://localhost:8088/media/abc/v1/master.m3u8"
          active={active}
        />
      </ThemeProvider>
    );
    await view.rerender(at(false));
    await view.rerender(at(true));
    expect(screen.queryByTestId('player-paused')).toBeNull();
    expect(player.play).toHaveBeenCalledTimes(3);
  });

  it('waits out the double-tap window before a tap pauses, and a double tap never pauses', async () => {
    jest.useFakeTimers();
    try {
      const onDoubleTap = jest.fn();
      await renderPlayer({ onDoubleTap });
      const player = mockPlayers[0]!;
      const tap = screen.getByTestId('player-tap');
      const at = { nativeEvent: { locationX: 120, locationY: 300 } };

      await fireEvent.press(tap, at);
      await fireEvent.press(tap, at);
      await act(async () => {
        jest.advanceTimersByTime(DOUBLE_TAP_MS * 2);
      });
      expect(onDoubleTap).toHaveBeenCalledWith({ x: 120, y: 300 });
      expect(player.pause).not.toHaveBeenCalled();

      await fireEvent.press(tap, at);
      expect(player.pause).not.toHaveBeenCalled();
      await act(async () => {
        jest.advanceTimersByTime(DOUBLE_TAP_MS);
      });
      expect(player.pause).toHaveBeenCalledTimes(1);
      expect(onDoubleTap).toHaveBeenCalledTimes(1);
    } finally {
      jest.useRealTimers();
    }
  });

  it('shows a seek bar once the length is known, and seeking moves the player', async () => {
    await renderPlayer();
    const player = mockPlayers[0]!;
    expect(screen.queryByTestId('player-seek')).toBeNull();

    await act(async () => emit(player, 'timeUpdate', { currentTime: 2 }));
    const bar = screen.getByTestId('player-seek');
    expect(bar.props.accessibilityValue.text).toBe('0:02 of 0:12');

    await fireEvent(bar, 'accessibilityAction', { nativeEvent: { actionName: 'increment' } });
    expect(player.currentTime).toBe(7);
  });

  it('hands one player from the feed to the post page and back: no restart, no pause', async () => {
    const feed = (
      <PlaybackScope>
        <VideoPlayer
          testID="feed"
          clipKey="post-1"
          hlsUrl="http://localhost:8088/a/master.m3u8"
          active
        />
      </PlaybackScope>
    );
    // The post page renders outside a scope here: on screen whatever the mocked focus says.
    const post = (
      <VideoPlayer
        testID="post"
        clipKey="post-1"
        hlsUrl="http://localhost:8088/a/master.m3u8"
        active
      />
    );
    const view = await render(<ThemeProvider>{feed}</ThemeProvider>);
    const player = mockPlayers[0]!;
    await act(async () => {
      (
        screen.getByTestId('video-view').props as { onFirstFrameRender: () => void }
      ).onFirstFrameRender();
    });
    expect(player.play).toHaveBeenCalledTimes(1);

    // The caption is tapped: the player is reserved, the feed screen loses focus, the post page opens.
    await act(async () => {
      handOverPlayer('post-1');
    });
    mockFocused = false;
    await view.rerender(
      <ThemeProvider>
        {feed}
        {post}
      </ThemeProvider>,
    );

    expect(mockPlayers).toHaveLength(1);
    expect(player.pause).not.toHaveBeenCalled();
    // One picture, on the post page, carrying on without the poster; the feed shows its poster.
    expect(screen.getAllByTestId('video-view')).toHaveLength(1);
    expect(screen.queryByTestId('post-poster')).toBeNull();
    expect(screen.getByTestId('feed-poster')).toBeTruthy();

    // Only the post page reports progress now.
    const onFeedProgress = jest.fn();
    await view.rerender(
      <ThemeProvider>
        <PlaybackScope>
          <VideoPlayer
            testID="feed"
            clipKey="post-1"
            hlsUrl="http://localhost:8088/a/master.m3u8"
            active
            onProgress={onFeedProgress}
          />
        </PlaybackScope>
        {post}
      </ThemeProvider>,
    );
    await act(async () => emit(player, 'timeUpdate', { currentTime: 4 }));
    expect(onFeedProgress).not.toHaveBeenCalled();

    // Back: the post page closes and the feed is in view again, with the same player.
    mockFocused = true;
    await view.rerender(<ThemeProvider>{feed}</ThemeProvider>);
    expect(mockPlayers).toHaveLength(1);
    expect(player.release).not.toHaveBeenCalled();
    expect(screen.getAllByTestId('video-view')).toHaveLength(1);
    expect(player.play.mock.calls.length).toBeGreaterThanOrEqual(1);
    expect(player.pause).not.toHaveBeenCalled();

    await view.unmount();
    expect(player.release).toHaveBeenCalledTimes(1);
    expect(livePlayerCount()).toBe(0);
  });

  it('uses a TextureView when asked (the post page, so rounded corners stay rounded)', async () => {
    await renderPlayer({ surface: 'textureView' });
    expect(screen.getByTestId('video-view').props.surfaceType).toBe('textureView');
  });
});
