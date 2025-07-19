import { fireEvent, render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import type { UploadEntry } from './types';
import { UploadRow } from './UploadRow';

jest.mock('expo-router', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  return {
    Link: ({ children }: { children: React.ReactNode }) =>
      React.createElement(React.Fragment, null, children),
  };
});

const entry = (patch: Partial<UploadEntry>): UploadEntry => ({
  localId: 'u1',
  idempotencyKey: 'k1',
  createdAt: 0,
  fileUri: 'file:///clip.mp4',
  fileName: 'clip.mp4',
  contentType: 'video/mp4',
  bytes: 1_000_000,
  durationMs: 10_000,
  width: null,
  height: null,
  clientMeta: { platform: 'android' },
  temporary: false,
  prepared: true,
  previewUri: 'file:///clip.mp4',
  state: 'PROCESSING',
  progress: 1,
  mediaId: 'm1',
  strategy: 'SINGLE',
  parts: {},
  error: null,
  draft: null,
  postIdempotencyKey: 'pk1',
  postId: null,
  clipId: null,
  attached: false,
  artistId: null,
  previewMediaId: null,
  previewState: 'NONE',
  previewSent: false,
  postError: null,
  ...patch,
});

async function renderRow(value: UploadEntry, onRemove: () => Promise<void>) {
  await render(
    <ThemeProvider>
      <UploadRow entry={value} onRetry={jest.fn()} onRemove={onRemove} onPostAgain={jest.fn()} />
    </ThemeProvider>,
  );
}

describe('UploadRow removal', () => {
  it('removes an upload without a post at once', async () => {
    const onRemove = jest.fn(async () => undefined);
    await renderRow(entry({ state: 'READY' }), onRemove);

    await fireEvent.press(screen.getByText('Remove'));

    expect(onRemove).toHaveBeenCalledTimes(1);
  });

  it('asks before removing an upload that is a post, because the post goes too', async () => {
    const onRemove = jest.fn(async () => undefined);
    await renderRow(entry({ postId: 'p1' }), onRemove);

    await fireEvent.press(screen.getByText('Remove'));
    expect(onRemove).not.toHaveBeenCalled();
    expect(screen.getByTestId('upload-u1-confirm')).toBeTruthy();

    await fireEvent.press(screen.getByText('Keep it'));
    expect(screen.queryByTestId('upload-u1-confirm')).toBeNull();

    await fireEvent.press(screen.getByText('Remove'));
    await fireEvent.press(screen.getByText('Delete post'));
    expect(onRemove).toHaveBeenCalledTimes(1);
  });

  it('removing a clip of a post of several takes only it out of the post', async () => {
    const onRemove = jest.fn(() => Promise.reject(new Error('offline')));
    await renderRow(entry({ postId: 'p1', clipId: 'c1', attached: true }), onRemove);

    await fireEvent.press(screen.getByText('Remove'));
    expect(screen.getByText(/takes it out of the post/)).toBeTruthy();
    await fireEvent.press(screen.getByText('Remove from post'));

    expect(onRemove).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('Could not take it out of the post. Try again.')).toBeTruthy();
  });

  it('says so when the post could not be deleted', async () => {
    await renderRow(entry({ postId: 'p1' }), () => Promise.reject(new Error('offline')));

    await fireEvent.press(screen.getByText('Remove'));
    await fireEvent.press(screen.getByText('Delete post'));

    expect(await screen.findByText('Could not delete the post. Try again.')).toBeTruthy();
  });
});
