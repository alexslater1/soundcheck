import { testDraft } from './testDrafts';
import { fireEvent, render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import type { PickedVideo } from './types';
import { UploadProblems } from './UploadProblems';
import { useUploadQueue } from './uploadQueue';

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

const mockStartUpload = jest.fn();
const mockRetryPost = jest.fn();
jest.mock('./uploadRunner', () => ({
  startUpload: (...args: unknown[]) => mockStartUpload(...args),
  retryPost: (...args: unknown[]) => mockRetryPost(...args),
}));

const video: PickedVideo = {
  uri: 'file:///clip.mp4',
  fileName: 'encore.mp4',
  contentType: 'video/mp4',
  bytes: 50_000_000,
  durationMs: 40_000,
  width: null,
  height: null,
};

function queue(
  ownerId: string,
  patch: Parameters<ReturnType<typeof useUploadQueue.getState>['patch']>[1],
) {
  const id = useUploadQueue.getState().enqueue(ownerId, video, { platform: 'android' });
  useUploadQueue.getState().patch(id, patch);
  return id;
}

const show = () =>
  render(
    <ThemeProvider>
      <UploadProblems ownerId="me" />
    </ThemeProvider>,
  );

describe('UploadProblems', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    useUploadQueue.setState({ entries: [] });
  });

  it('shows nothing while every upload is fine', async () => {
    queue('me', { state: 'UPLOADING' });
    await show();
    expect(screen.queryByTestId('upload-problems')).toBeNull();
  });

  it('says in plain words that an upload failed, retries it, and shows the error behind Details', async () => {
    const id = queue('me', {
      state: 'FAILED',
      error: 'java.lang.OutOfMemoryError: Failed to allocate a 8388624 byte allocation',
    });
    queue('someone-else', { state: 'FAILED', error: 'timeout' });
    await show();

    expect(screen.getByTestId('upload-problems-text')).toHaveTextContent(
      'Upload failed — the phone ran out of memory. Try again',
    );
    expect(screen.queryByText(/OutOfMemoryError/)).toBeNull();

    await fireEvent.press(screen.getByTestId('upload-problems-details'));
    expect(screen.getByTestId(`upload-problem-${id}`)).toHaveTextContent(
      'encore.mp4: java.lang.OutOfMemoryError: Failed to allocate a 8388624 byte allocation',
    );

    await fireEvent.press(screen.getByTestId('upload-problems-retry'));
    expect(mockStartUpload).toHaveBeenCalledTimes(1);
    expect(mockStartUpload).toHaveBeenCalledWith(id);
  });

  it('sends a failed post again', async () => {
    const draft = testDraft({ caption: 'Encore', gigId: 'g1' });
    const posted = queue('me', { state: 'READY', draft, postError: 'Could not reach the server' });
    await show();
    expect(screen.getByTestId('upload-problems-text')).toHaveTextContent(
      'Posting failed — the connection dropped. Try again',
    );
    await fireEvent.press(screen.getByTestId('upload-problems-retry'));
    expect(mockRetryPost).toHaveBeenCalledWith(posted);
  });

  it('offers no retry for a clip the server rejected', async () => {
    queue('me', { state: 'REJECTED' });
    await show();
    expect(screen.getByTestId('upload-problems-text')).toHaveTextContent(
      /could not be used.*Remove it below/,
    );
    expect(screen.queryByTestId('upload-problems-retry')).toBeNull();
  });
});
