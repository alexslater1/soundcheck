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
  replace: jest.Mock;
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
          replace: jest.fn(),
          listeners: {},
          source,
        };
        setup?.(player);
        mockPlayers.push(player);
        ref.current = player;
      }
      return ref.current;
    },
    VideoView: (props: object) => React.createElement(View, { testID: 'video-view', ...props }),
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
    expect(player.source).toEqual({ uri: 'http://localhost:8088/media/abc/v1/master.m3u8' });
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

    await act(async () => mockPlayers[0]!.listeners.playingChange?.({ isPlaying: true }));

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

  it('shows an error with a retry when the player fails, and reloads the source on retry', async () => {
    await renderPlayer();
    const player = mockPlayers[0]!;

    await act(async () => player.listeners.statusChange?.({ status: 'error' }));
    expect(screen.getByTestId('player-error')).toBeTruthy();

    await fireEvent.press(screen.getByText('Try again'));

    expect(player.replace).toHaveBeenCalledWith({
      uri: 'http://localhost:8088/media/abc/v1/master.m3u8',
    });
    expect(screen.queryByTestId('player-error')).toBeNull();
  });
});
