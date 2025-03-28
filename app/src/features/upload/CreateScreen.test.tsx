import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import { useAuthStore } from '@/features/auth/store';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { CreateScreen } from './CreateScreen';
import type { PickResult } from './pickVideo';
import type { PickedVideo } from './types';
import { useUploadQueue } from './uploadQueue';

const mockPick = jest.fn<Promise<PickResult>, []>();
const mockStartUpload = jest.fn(async (_localId: string) => undefined);
const mockResume = jest.fn();
const mockCompress = jest.fn();
const mockDiscard = jest.fn();
const mockRemove = jest.fn((localId: string) => useUploadQueue.getState().remove(localId));

jest.mock('./pickVideo', () => ({
  pickFromLibrary: () => mockPick(),
  recordWithCamera: () => mockPick(),
}));
jest.mock('./uploadRunner', () => ({
  startUpload: (localId: string) => mockStartUpload(localId),
  resumeProcessing: (ownerId: string) => mockResume(ownerId),
  removeUpload: (localId: string) => mockRemove(localId),
}));
jest.mock('./compress', () => ({
  compressVideo: (...args: unknown[]) => mockCompress(...args),
  discardFile: (uri: string) => mockDiscard(uri),
}));
jest.mock('expo-router', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  return {
    Link: ({ children }: { children: React.ReactNode }) =>
      React.createElement(React.Fragment, null, children),
  };
});

const video: PickedVideo = {
  uri: 'file:///DCIM/clip.mp4',
  fileName: 'clip.mp4',
  contentType: 'video/mp4',
  bytes: 60 * 1024 * 1024,
  durationMs: 20_000,
  width: 1080,
  height: 1920,
};

function renderScreen() {
  return render(
    <ThemeProvider>
      <CreateScreen />
    </ThemeProvider>,
  );
}

describe('CreateScreen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    useUploadQueue.setState({ entries: [] });
    useAuthStore.setState({ me: { id: 'user-1' } as never });
    mockCompress.mockResolvedValue(null);
  });

  it('starts with an empty list and resumes polling for uploads still being processed', async () => {
    await renderScreen();

    expect(screen.getByTestId('uploads-empty')).toBeTruthy();
    expect(mockResume).toHaveBeenCalledWith('user-1');
  });

  it('compresses the picked clip, queues the smaller file with both sizes recorded, and starts the upload', async () => {
    mockPick.mockResolvedValue({ kind: 'picked', video });
    mockCompress.mockResolvedValue({
      uri: 'file:///cache/compressed.mp4',
      bytes: 18 * 1024 * 1024,
      elapsedMs: 4200,
    });
    await renderScreen();

    await fireEvent.press(screen.getByText('Choose a video'));

    await waitFor(() => expect(mockStartUpload).toHaveBeenCalledTimes(1));
    const [entry] = useUploadQueue.getState().entries;
    expect(entry).toMatchObject({
      ownerId: 'user-1',
      fileUri: 'file:///cache/compressed.mp4',
      bytes: 18 * 1024 * 1024,
      temporary: true, // the app's own file: deleted once the server has the bytes
      state: 'QUEUED',
      clientMeta: {
        originalBytes: 60 * 1024 * 1024,
        compressedBytes: 18 * 1024 * 1024,
        compressMs: 4200,
      },
    });
    expect(mockStartUpload).toHaveBeenCalledWith(entry!.localId);
    expect(screen.getByText('Compressed 60.0 MB → 18.0 MB (70% smaller)')).toBeTruthy();
    expect(mockDiscard).not.toHaveBeenCalled();
  });

  it('uploads the original and deletes the compressed file when compression made it bigger', async () => {
    mockPick.mockResolvedValue({ kind: 'picked', video });
    mockCompress.mockResolvedValue({
      uri: 'file:///cache/compressed.mp4',
      bytes: 70 * 1024 * 1024,
      elapsedMs: 4200,
    });
    await renderScreen();

    await fireEvent.press(screen.getByText('Choose a video'));

    await waitFor(() => expect(mockStartUpload).toHaveBeenCalledTimes(1));
    expect(useUploadQueue.getState().entries[0]).toMatchObject({
      fileUri: 'file:///DCIM/clip.mp4',
      temporary: false, // the user's own file is never deleted
    });
    expect(mockDiscard).toHaveBeenCalledWith('file:///cache/compressed.mp4');
  });

  it('refuses a clip that is too long without uploading anything', async () => {
    mockPick.mockResolvedValue({ kind: 'picked', video: { ...video, durationMs: 75_000 } });
    await renderScreen();

    await fireEvent.press(screen.getByText('Choose a video'));

    expect(await screen.findByText('Clips can be up to 60 seconds long.')).toBeTruthy();
    expect(mockCompress).not.toHaveBeenCalled();
    expect(mockStartUpload).not.toHaveBeenCalled();
    expect(useUploadQueue.getState().entries).toHaveLength(0);
  });

  it('does nothing when the picker is cancelled, and explains a denied camera permission', async () => {
    mockPick.mockResolvedValueOnce({ kind: 'cancelled' });
    await renderScreen();
    await fireEvent.press(screen.getByText('Choose a video'));
    await waitFor(() => expect(mockPick).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('create-problem')).toBeNull();

    mockPick.mockResolvedValueOnce({ kind: 'denied' });
    await fireEvent.press(screen.getByText('Record a clip'));
    expect(await screen.findByText(/Camera access was not granted/)).toBeTruthy();
  });

  it('shows each upload’s state; a failed one can be retried and removed, a ready one watched', async () => {
    const failed = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    const ready = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    useUploadQueue.getState().enqueue('someone-else', video, { platform: 'android' });
    useUploadQueue
      .getState()
      .patch(failed, { state: 'FAILED', error: 'network down', progress: 0.8 });
    useUploadQueue.getState().patch(ready, { state: 'READY', mediaId: 'media-9', progress: 1 });
    await renderScreen();

    // Only this account's uploads are listed.
    expect(screen.getAllByText('clip.mp4')).toHaveLength(2);
    expect(screen.getByTestId(`upload-${ready}-state`).props.children).toBe('Ready');
    expect(screen.getByText('Watch')).toBeTruthy();
    expect(screen.getByText('network down')).toBeTruthy();

    await fireEvent.press(screen.getByText('Retry'));
    expect(mockStartUpload).toHaveBeenCalledWith(failed);

    await act(async () =>
      useUploadQueue.getState().patch(failed, { state: 'UPLOADING', error: null, progress: 0.9 }),
    );
    expect(screen.getByTestId(`upload-${failed}-state`).props.children).toBe('Uploading 90%');
    expect(screen.getByRole('progressbar').props.accessibilityValue).toEqual({
      min: 0,
      max: 100,
      now: 90,
    });

    await act(async () =>
      useUploadQueue.getState().patch(failed, { state: 'FAILED', error: 'again' }),
    );
    await fireEvent.press(screen.getAllByText('Remove')[1]!);
    expect(mockRemove).toHaveBeenCalledWith(failed);
    expect(useUploadQueue.getState().entries.map((e) => e.localId)).not.toContain(failed);
  });
});
