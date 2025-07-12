import { render, screen } from '@testing-library/react-native';

import type { Post } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { PostMedia } from './PostMedia';

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
// The player is native; here it is a labelled box.
jest.mock('@/features/player/VideoPlayer', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { View } = jest.requireActual<typeof import('react-native')>('react-native');
  return { VideoPlayer: () => React.createElement(View, { testID: 'post-player' }) };
});
jest.mock('@/features/telemetry/TelemetryScope', () => ({ useTrack: () => jest.fn() }));

const post = (patch: Partial<Post>): Post =>
  ({
    id: 'p1',
    status: 'PENDING_MEDIA',
    playback: { status: 'PROCESSING' },
    ...patch,
  }) as Post;

const show = (value: Post) =>
  render(
    <ThemeProvider>
      <PostMedia post={value} active />
    </ThemeProvider>,
  );

describe('PostMedia while the clip is not playable', () => {
  it('says the clip is processing and roughly how long it takes, instead of a blank box', async () => {
    await show(post({}));

    expect(screen.getByTestId('post-processing')).toBeTruthy();
    expect(screen.getByText('Processing your clip…')).toBeTruthy();
    expect(
      screen.getByText('Usually under a minute; a long clip can take a few minutes.'),
    ).toBeTruthy();
  });

  it('says so when processing failed, and plays the clip once it is ready', async () => {
    await show(post({ status: 'FAILED', playback: { status: 'FAILED' } }));
    expect(screen.getByText('This clip could not be processed.')).toBeTruthy();
    expect(screen.queryByTestId('post-processing')).toBeNull();

    await show(
      post({
        status: 'PUBLISHED',
        playback: { status: 'READY', hlsUrl: 'http://localhost:8088/media/m1/v1/master.m3u8' },
      }),
    );
    expect(screen.getByTestId('post-player')).toBeTruthy();
  });
});
