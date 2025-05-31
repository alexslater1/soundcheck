import { act, fireEvent, render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { usePlayerPrefs } from './playerPrefs';
import { VideoPlayer } from './VideoPlayer';

type Listener = (payload: Record<string, unknown>) => void;

type FakePlayer = {
  loop: boolean;
  muted: boolean;
  duration: number;
  timeUpdateEventInterval: number;
  play: jest.Mock;
  pause: jest.Mock;
  replaceAsync: jest.Mock;
  listeners: Record<string, Listener>;
  source: unknown;
};

// The native module is not there under Jest: a fake player records what the component asks of it.
const mockPlayers: FakePlayer[] = [];

jest.mock('expo-video', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { View } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    useVideoPlayer: (source: unknown, setup?: (player: FakePlayer) => void) => {
      const ref = React.useRef<FakePlayer | null>(null);
      if (ref.current === null) {
        const player: FakePlayer = {
          loop: false,
          muted: false,
          duration: 12.5,
          timeUpdateEventInterval: 0,
          play: jest.fn(),
          pause: jest.fn(),
          replaceAsync: jest.fn(() => Promise.resolve()),
          listeners: {},
          source,
        };
        setup?.(player);
        mockPlayers.push(player);
        ref.current = player;
      }
      return ref.current;
    },
    VideoView: (props: { onFirstFrameRender?: () => void }) =>
      React.createElement(View, { testID: 'video-view', ...props }),
  };
});

// Icon fonts are loaded through expo-asset, which is not available under Jest.
jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

jest.mock('expo', () => ({
  ...jest.requireActual<typeof import('expo')>('expo'),
  useEventListener: (player: FakePlayer, event: string, listener: Listener) => {
    player.listeners[event] = listener;
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

  it('toggles sound for every player with one tap, and labels the control', async () => {
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

    await act(async () => mockPlayers[0]!.listeners.timeUpdate?.({ currentTime: 3.25 }));

    expect(onProgress).toHaveBeenCalledWith({ positionMs: 3250, durationMs: 12_500 });
  });

  it('retries a failed clip once by itself with a new player, then offers Try again', async () => {
    jest.useFakeTimers();
    try {
      await renderPlayer();
      const first = mockPlayers[0]!;

      // First failure: no error shown; a moment later a fresh player is built (the old one may have
      // been released, and a released player cannot be handed to the native view again).
      await act(async () => first.listeners.statusChange?.({ status: 'error' }));
      expect(screen.queryByTestId('player-error')).toBeNull();
      await act(async () => {
        jest.advanceTimersByTime(1500);
      });
      expect(mockPlayers).toHaveLength(2);
      expect(first.replaceAsync).not.toHaveBeenCalled();

      // Second failure: the viewer is told, and Try again builds another new player.
      await act(async () => mockPlayers[1]!.listeners.statusChange?.({ status: 'error' }));
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
});
