import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { LOCAL_FILE_BUFFER } from './localPlayback';
import type { PickResult } from './pickVideo';
import { clipLength, RecordClipButton } from './RecordClipButton';
import type { PickedVideo } from './types';

const mockRecord = jest.fn<Promise<PickResult>, []>();
const mockDiscard = jest.fn();
const mockPlay = jest.fn();

jest.mock('./pickVideo', () => ({ recordWithCamera: () => mockRecord() }));
jest.mock('./compress', () => ({ discardFile: (uri: string) => mockDiscard(uri) }));
jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({ top: 24, bottom: 16, left: 0, right: 0 }),
}));
// The native player is not there under Jest: the review's player is a box that records `play`, and
// the buffer limits it had when it started.
jest.mock('expo-video', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { View } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    useVideoPlayer: (_source: unknown, setup?: (player: unknown) => void) => {
      const player = React.useRef({
        loop: true,
        bufferOptions: undefined as unknown,
        play() {
          mockPlay(this.bufferOptions);
        },
      }).current;
      React.useEffect(() => setup?.(player), [player, setup]);
      return player;
    },
    VideoView: (props: Record<string, unknown>) => React.createElement(View, props),
  };
});

const take = (patch: Partial<PickedVideo> = {}): PickedVideo => ({
  uri: 'file:///cache/ImagePicker/take-1.mp4',
  fileName: null,
  contentType: 'video/mp4',
  bytes: 85 * 1024 * 1024,
  durationMs: 42_000,
  width: 1080,
  height: 1920,
  ...patch,
});

async function show(onPick = jest.fn()) {
  await render(
    <ThemeProvider>
      <RecordClipButton disabled={false} onPick={onPick} />
    </ThemeProvider>,
  );
  return onPick;
}

/** What the Create tab would get from the pick it was handed. */
async function picked(onPick: jest.Mock): Promise<PickResult> {
  const pick = onPick.mock.calls.at(-1)?.[0] as () => Promise<PickResult>;
  return pick();
}

describe('RecordClipButton', () => {
  beforeEach(() => jest.clearAllMocks());

  it('plays a new recording in the app before it is used, with the player’s seek bar', async () => {
    mockRecord.mockResolvedValue({ kind: 'picked', videos: [take()] });
    const onPick = await show();

    await fireEvent.press(screen.getByTestId('record-clip'));

    expect(await screen.findByTestId('clip-review')).toBeTruthy();
    expect(screen.getByTestId('clip-review-video').props.nativeControls).toBe(true);
    expect(screen.getByTestId('clip-review-facts')).toHaveTextContent('0:42 · 85.0 MB');
    // Limited before it starts: a 4K take buffered whole would fill the Java heap (2025-08-06).
    expect(mockPlay).toHaveBeenCalledWith(LOCAL_FILE_BUFFER);
    expect(onPick).not.toHaveBeenCalled();

    await fireEvent.press(screen.getByTestId('clip-review-use'));
    expect(screen.queryByTestId('clip-review')).toBeNull();
    expect(await picked(onPick)).toEqual({ kind: 'picked', videos: [take()] });
    expect(mockDiscard).not.toHaveBeenCalled();
  });

  it('Retake deletes the take and opens the camera again', async () => {
    mockRecord.mockResolvedValueOnce({ kind: 'picked', videos: [take()] }).mockResolvedValueOnce({
      kind: 'picked',
      videos: [take({ uri: 'file:///cache/ImagePicker/take-2.mp4' })],
    });
    const onPick = await show();
    await fireEvent.press(screen.getByTestId('record-clip'));
    await screen.findByTestId('clip-review');

    await fireEvent.press(screen.getByTestId('clip-review-retake'));

    expect(mockDiscard).toHaveBeenCalledWith('file:///cache/ImagePicker/take-1.mp4');
    await waitFor(() => expect(mockRecord).toHaveBeenCalledTimes(2));
    await fireEvent.press(await screen.findByTestId('clip-review-use'));
    expect((await picked(onPick)).kind).toBe('picked');
    expect(await picked(onPick)).toMatchObject({
      videos: [{ uri: 'file:///cache/ImagePicker/take-2.mp4' }],
    });
  });

  it('Discard deletes the take and uses nothing', async () => {
    mockRecord.mockResolvedValue({ kind: 'picked', videos: [take()] });
    const onPick = await show();
    await fireEvent.press(screen.getByTestId('record-clip'));
    await screen.findByTestId('clip-review');

    await fireEvent.press(screen.getByTestId('clip-review-discard'));

    expect(screen.queryByTestId('clip-review')).toBeNull();
    expect(mockDiscard).toHaveBeenCalledWith('file:///cache/ImagePicker/take-1.mp4');
    expect(onPick).not.toHaveBeenCalled();
  });

  it('will not use a take that is too long, and says why', async () => {
    mockRecord.mockResolvedValue({ kind: 'picked', videos: [take({ durationMs: 200_000 })] });
    await show();
    await fireEvent.press(screen.getByTestId('record-clip'));

    expect(await screen.findByTestId('clip-review-problem')).toHaveTextContent(
      'Clips can be up to 3 minutes long.',
    );
    expect(screen.getByTestId('clip-review-use')).toBeDisabled();
  });

  it('hands a cancelled or refused camera straight on, and an error as a failed pick', async () => {
    mockRecord.mockResolvedValueOnce({ kind: 'denied' });
    const onPick = await show();
    await fireEvent.press(screen.getByTestId('record-clip'));
    await waitFor(() => expect(onPick).toHaveBeenCalledTimes(1));
    expect(await picked(onPick)).toEqual({ kind: 'denied' });
    expect(screen.queryByTestId('clip-review')).toBeNull();

    mockRecord.mockRejectedValueOnce(new Error('camera busy'));
    await fireEvent.press(screen.getByTestId('record-clip'));
    await waitFor(() => expect(onPick).toHaveBeenCalledTimes(2));
    await expect(picked(onPick)).rejects.toThrow('camera busy');
  });
});

describe('clipLength', () => {
  it('reads minutes and seconds', () => {
    expect(clipLength(42_000)).toBe('0:42');
    expect(clipLength(125_400)).toBe('2:05');
    expect(clipLength(null)).toBeNull();
  });
});
