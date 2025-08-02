import { act, fireEvent, render, screen } from '@testing-library/react-native';

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
const mockTrack = jest.fn();
jest.mock('@/features/telemetry/TelemetryScope', () => ({ useTrack: () => mockTrack }));
const mockLike = jest.fn(async (_postId: string) => ({ liked: true, likeCount: 1 }));
jest.mock('@/api/generated/engagement/engagement', () => ({
  likePost: (postId: string) => mockLike(postId),
  unlikePost: jest.fn(),
}));

const post = (patch: Partial<Post>): Post =>
  ({
    id: 'p1',
    status: 'PENDING_MEDIA',
    playback: { status: 'PROCESSING' },
    ...patch,
  }) as Post;

const show = (value: Post, active = true) =>
  render(
    <ThemeProvider>
      <PostMedia post={value} active={active} />
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

describe('PostMedia with a photo', () => {
  const photo = post({
    id: 'p-photo',
    status: 'PUBLISHED',
    likedByMe: false,
    likeCount: 0,
    playback: {
      status: 'READY',
      kind: 'IMAGE',
      posterUrl: 'http://localhost:8088/media/m2/v1/image.jpg',
      thumbUrl: 'http://localhost:8088/media/m2/v1/thumb.jpg',
      blurhash: 'LKTLyT|{fQ|{',
      width: 1200,
      height: 1600,
    },
  });

  beforeEach(() => {
    jest.useFakeTimers();
    mockTrack.mockClear();
    mockLike.mockClear();
  });
  afterEach(() => jest.useRealTimers());

  it('shows the photo itself, with no player and no sound button', async () => {
    await show(photo);

    expect(screen.getByTestId('post-photo')).toBeTruthy();
    expect(screen.getByTestId('post-photo-image')).toBeTruthy();
    expect(screen.queryByTestId('post-player')).toBeNull();
    expect(screen.queryByLabelText('Turn sound on')).toBeNull();
    expect(screen.queryByLabelText('Turn sound off')).toBeNull();
  });

  it('reports its time on screen as the start, each quarter and the end, once', async () => {
    await show(photo);

    await act(async () => {
      jest.advanceTimersByTime(1_000);
    });
    expect(mockTrack.mock.calls).toEqual([['VIEW_START', 'p-photo', undefined]]);

    await act(async () => {
      jest.advanceTimersByTime(10_000);
    });
    expect(mockTrack.mock.calls).toEqual([
      ['VIEW_START', 'p-photo', undefined],
      ['WATCH_PROGRESS', 'p-photo', 0.25],
      ['WATCH_PROGRESS', 'p-photo', 0.5],
      ['WATCH_PROGRESS', 'p-photo', 0.75],
      ['COMPLETE', 'p-photo', undefined],
    ]);
  });

  it('reports nothing while it is not the item in view', async () => {
    await show(photo, false);

    await act(async () => {
      jest.advanceTimersByTime(10_000);
    });
    expect(mockTrack).not.toHaveBeenCalled();
  });

  it('likes the post on a double tap', async () => {
    await show(photo);

    const target = screen.getByTestId('post-photo-tap');
    const at = { nativeEvent: { locationX: 10, locationY: 20 } };
    await fireEvent.press(target, at);
    await fireEvent.press(target, at);

    expect(mockLike).toHaveBeenCalledWith('p-photo');
  });
});
