import { act, fireEvent, render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { ClipPreview } from './ClipPreview';
import { LOCAL_FILE_BUFFER } from './localPlayback';
import { usePreviewPlaying } from './previewPlaying';

const mockPlayers: Record<string, unknown>[] = [];
type Players = { play: jest.Mock; pause: jest.Mock };

jest.mock('@react-navigation/native', () => ({ useIsFocused: () => true }));
// The native player is not there under Jest: each preview's player is a plain object, kept so the
// test can read what the preview set on it.
jest.mock('expo-video', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { View } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    useVideoPlayer: (_source: unknown, setup?: (player: Record<string, unknown>) => void) => {
      const player = React.useRef<Record<string, unknown> | null>(null);
      if (player.current === null) {
        player.current = { play: jest.fn(), pause: jest.fn() };
        setup?.(player.current);
        mockPlayers.push(player.current);
      }
      return player.current;
    },
    VideoView: (props: Record<string, unknown>) => React.createElement(View, props),
  };
});

describe('ClipPreview', () => {
  it('limits how far each preview reads ahead, so several 4K clips fit in the Java heap', async () => {
    await render(
      <ThemeProvider>
        <ClipPreview uri="file:///picked/a.mp4" testID="preview-a" />
        <ClipPreview uri="file:///picked/b.mp4" testID="preview-b" />
      </ThemeProvider>,
    );

    expect(screen.getByTestId('preview-b')).toBeTruthy();
    expect(mockPlayers).toHaveLength(2);
    for (const player of mockPlayers) {
      expect(player.bufferOptions).toEqual(LOCAL_FILE_BUFFER);
      expect(player.loop).toBe(true);
    }
  });

  it('pauses on a second tap, and plays one preview at a time', async () => {
    mockPlayers.length = 0;
    usePreviewPlaying.setState({ playing: null });
    await render(
      <ThemeProvider>
        <ClipPreview uri="file:///picked/a.mp4" testID="preview-a" />
        <ClipPreview uri="file:///picked/b.mp4" testID="preview-b" />
      </ThemeProvider>,
    );
    const [a, b] = mockPlayers as [Players, Players];

    await act(async () => fireEvent.press(screen.getByTestId('preview-a')));
    expect(a.play).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('preview-a').props.accessibilityLabel).toBe('Pause the clip');

    // Playing b pauses a.
    a.pause.mockClear();
    await act(async () => fireEvent.press(screen.getByTestId('preview-b')));
    expect(b.play).toHaveBeenCalledTimes(1);
    expect(a.pause).toHaveBeenCalled();
    expect(screen.getByTestId('preview-a').props.accessibilityLabel).toBe('Play the clip');

    // A second tap pauses b.
    b.pause.mockClear();
    await act(async () => fireEvent.press(screen.getByTestId('preview-b')));
    expect(b.pause).toHaveBeenCalled();
    expect(usePreviewPlaying.getState().playing).toBeNull();
  });
});
