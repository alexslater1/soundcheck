import { act, fireEvent, render, screen, within } from '@testing-library/react-native';

import { PixelRatio, StyleSheet } from 'react-native';

import type { Post, PostClip } from '@/api/generated/model';
import { useAuthStore } from '@/features/auth/store';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { ClipCarousel, frameOf, snapIntervalFor } from './ClipCarousel';

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
const mockPlayerProps = jest.fn();
// The player is native; here it is a box that says which clip it plays and whether it is active.
jest.mock('@/features/player/VideoPlayer', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { View } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    VideoPlayer: (props: { clipKey: string; active: boolean; live: boolean }) => {
      mockPlayerProps(props);
      const { clipKey, active, live } = props;
      return React.createElement(View, {
        testID: `player-${clipKey}-${active ? 'active' : live === false ? 'poster' : 'idle'}`,
      });
    },
  };
});
jest.mock('@/features/telemetry/TelemetryScope', () => ({ useTrack: () => jest.fn() }));
jest.mock('./useLike', () => ({ useLike: () => ({ like: jest.fn() }) }));

const WIDTH = 300;
const author = { id: 'u-author', handle: 'maya', displayName: 'Maya' };
const friend = { id: 'u-friend', handle: 'sam', displayName: 'Sam' };
const keo = { id: 'a-keo', name: 'Keo' };
const cards = { id: 'a-cards', name: 'Cardinals' };

const ready = (id: string, act?: typeof keo, owner = author): PostClip =>
  ({
    id,
    position: 0,
    act,
    owner,
    status: 'READY',
    playback: { status: 'READY', hlsUrl: `http://media/${id}/master.m3u8` },
  }) as PostClip;

const postWith = (clips: PostClip[], patch: Partial<Post> = {}): Post =>
  ({
    id: 'p1',
    status: 'PUBLISHED',
    author,
    playback: clips[0]?.playback ?? { status: 'READY' },
    clips,
    clipCount: clips.length,
    withUsers: [],
    likeCount: 0,
    likedByMe: false,
    ...patch,
  }) as unknown as Post;

async function show(post: Post, props: Partial<Parameters<typeof ClipCarousel>[0]> = {}) {
  await render(
    <ThemeProvider>
      <ClipCarousel post={post} active {...props} />
    </ThemeProvider>,
  );
  const frame = screen.queryByTestId('clip-frame');
  if (frame) {
    // The width comes from layout, which the test renderer does not do by itself.
    await act(async () => {
      fireEvent(frame, 'layout', {
        nativeEvent: { layout: { width: WIDTH, height: 500, x: 0, y: 0 } },
      });
    });
  }
}

async function swipeTo(index: number) {
  const list = screen.getByTestId('clip-list');
  await act(async () => {
    // The list measures itself before it can tell which clips are near the one in view.
    fireEvent(list, 'layout', {
      nativeEvent: { layout: { width: WIDTH, height: 500, x: 0, y: 0 } },
    });
    fireEvent(list, 'contentSizeChange', WIDTH * 5, 500);
  });
  await act(async () => {
    fireEvent.scroll(screen.getByTestId('clip-list'), {
      nativeEvent: {
        contentOffset: { x: index * WIDTH, y: 0 },
        contentSize: { width: WIDTH * 5, height: 500 },
        layoutMeasurement: { width: WIDTH, height: 500 },
      },
    });
    // The list renders the clips around the new position in its next batch.
    await new Promise((resolve) => setTimeout(resolve, 100));
  });
}

describe('ClipCarousel', () => {
  beforeEach(() => useAuthStore.setState({ me: null } as never));

  it('is just the clip for a post of one clip: no counter, no dots', async () => {
    await show(postWith([ready('c1', keo)]));
    expect(screen.queryByTestId('clip-carousel')).toBeNull();
    expect(screen.getByTestId('player-c1-active')).toBeTruthy();
    expect(screen.queryByTestId('clip-counter')).toBeNull();
    // Its act still shows.
    expect(screen.getByTestId('act-chip-a-keo')).toBeOnTheScreen();
  });

  it('shows no act chip for a post of one clip with no act', async () => {
    await show(postWith([ready('c1')]));
    expect(screen.queryByTestId('act-bar')).toBeNull();
  });

  it('plays only the clip in view, and swiping moves the counter, the dots and the act highlight', async () => {
    const onClipChange = jest.fn();
    await show(
      postWith([ready('c1', cards), ready('c2', cards), ready('c3', keo), ready('c4', keo)]),
      { onClipChange },
    );

    expect(screen.getByTestId('clip-counter').props.children).toEqual([1, '/', 4]);
    expect(screen.getByTestId('player-c1-active')).toBeTruthy();
    expect(screen.getByTestId('player-c2-idle')).toBeTruthy();
    expect(screen.getByTestId('act-chip-a-cards').props.accessibilityState.selected).toBe(true);
    expect(onClipChange).toHaveBeenLastCalledWith(expect.objectContaining({ id: 'c1' }));

    await swipeTo(2);
    expect(screen.getByTestId('clip-counter').props.children).toEqual([3, '/', 4]);
    expect(screen.getByTestId('player-c3-active')).toBeTruthy();
    expect(screen.queryByTestId('player-c1-active')).toBeNull();
    // Across the boundary: the next act is highlighted.
    expect(screen.getByTestId('act-chip-a-keo').props.accessibilityState.selected).toBe(true);
    expect(screen.getByTestId('act-chip-a-cards').props.accessibilityState.selected).toBe(false);
    expect(onClipChange).toHaveBeenLastCalledWith(expect.objectContaining({ id: 'c3' }));
  });

  it('keeps the clips either side ready only for the post in view (a feed holds three posts live)', async () => {
    const clips = [ready('c1'), ready('c2'), ready('c3')];
    await show(postWith(clips), { active: false });
    // A post above or below the one in view: only the clip it shows has a player.
    expect(screen.getByTestId('player-c1-idle')).toBeTruthy();
    expect(screen.getByTestId('player-c2-poster')).toBeTruthy();
  });

  it("draws a clip close to the box at the box's shape, and a clip of another shape at its own", () => {
    const sized = (width: number, height: number) =>
      ({ ...ready('c1'), playback: { status: 'READY', width, height } }) as PostClip;
    // A 9:16 phone clip on a card taller than 9:16 fills the card (2025-10-17: drawn at 9:16 it
    // left black above and below, and its seek bar under the caption).
    expect(frameOf(sized(1080, 1920), 0.5)).toBe(0.5);
    // A landscape clip in a portrait box is shown whole, at its own shape.
    expect(frameOf(sized(1920, 1080), 0.5)).toBeCloseTo(16 / 9);
    // Size unknown: the box.
    expect(frameOf(ready('c1'), 0.5)).toBe(0.5);
  });

  it('makes each clip exactly as wide as the frame and snaps to that width (no sliver of the next)', async () => {
    await render(
      <ThemeProvider>
        <ClipCarousel post={postWith([ready('c1'), ready('c2')])} active />
      </ThemeProvider>,
    );
    // A width that is not a whole number of points, as on a 411.4 pt phone.
    await act(async () => {
      fireEvent(screen.getByTestId('clip-frame'), 'layout', {
        nativeEvent: { layout: { width: 363.43, height: 500, x: 0, y: 0 } },
      });
    });
    expect(StyleSheet.flatten(screen.getByTestId('clip-c1').props.style).width).toBe(363.43);
    expect(screen.getByTestId('clip-list').props.snapToInterval).toBe(
      snapIntervalFor(363.43, PixelRatio.get()),
    );
  });

  it("gives Android a snap interval that truncates to the clips' own pixel width", () => {
    // What Android does with it, in 32-bit floats: (interval * density).toInt().
    const toPixels = (interval: number, density: number) =>
      Math.trunc(Math.fround(Math.fround(interval) * Math.fround(density)));
    // On the phone: 954 px clips at density 2.625. The width itself came out 1 px short.
    expect(toPixels(954 / 2.625, 2.625)).toBe(953);
    expect(toPixels(snapIntervalFor(954 / 2.625, 2.625), 2.625)).toBe(954);
    for (const density of [2, 2.625, 2.75, 3, 3.5]) {
      for (let pixels = 600; pixels < 1400; pixels++) {
        expect(toPixels(snapIntervalFor(pixels / density, density), density)).toBe(pixels);
      }
    }
  });

  it('centres the dots on the clip even when the overlay leaves room for the sound button', async () => {
    const clips = [ready('c1'), ready('c2')];
    const shiftOf = () =>
      (
        StyleSheet.flatten(screen.getByTestId('clip-dots').props.style) as {
          transform: { translateX: number }[];
        }
      ).transform[0]?.translateX;

    await show(postWith(clips));
    expect(shiftOf()).toBe(0);

    await screen.unmount();
    await show(postWith(clips), { soundAtTop: true });
    // Overlay inset 8 left, 64 right: the dots move right by half of the 56 difference.
    expect(shiftOf()).toBe(28);
  });

  it('holds a photo as a slide of its own: shaped like any clip, swiped to like one, with its act', async () => {
    const photo = {
      id: 'p2',
      position: 1,
      act: keo,
      owner: author,
      status: 'READY',
      playback: {
        status: 'READY',
        kind: 'IMAGE',
        posterUrl: 'http://media/p2/v1/image.jpg',
        width: 1600,
        height: 1200,
      },
    } as PostClip;
    await show(postWith([ready('c1', cards), photo]));

    // A landscape photo in the post's portrait box: shown whole over a blurred copy of itself.
    expect(screen.getByTestId('clip-p2-shaped')).toBeTruthy();
    expect(screen.getByTestId('clip-p2-backdrop')).toBeTruthy();
    expect(screen.getByTestId('player-c1-active')).toBeTruthy();

    await swipeTo(1);
    expect(screen.getByTestId('clip-counter').props.children).toEqual([2, '/', 2]);
    expect(screen.getByTestId('post-photo')).toBeTruthy();
    expect(screen.queryByTestId('player-c1-active')).toBeNull();
    expect(screen.getByTestId('act-chip-a-keo').props.accessibilityState.selected).toBe(true);
  });

  it('keeps one sound button still while the clips swipe past, for the clip in view', async () => {
    const silent = ready('c2');
    silent.playback = { ...silent.playback, hasAudio: false };
    const photo = {
      ...ready('c3'),
      playback: { status: 'READY', kind: 'IMAGE', posterUrl: 'http://media/c3/image.jpg' },
    } as PostClip;
    mockPlayerProps.mockClear();
    await show(postWith([ready('c1'), silent, photo]), { soundAtTop: true });

    // The clips draw none of their own: the carousel's sits over them all.
    expect(mockPlayerProps).toHaveBeenCalledWith(expect.objectContaining({ soundButton: false }));
    const button = screen.getByTestId('clip-carousel-sound');
    expect(button).toBeOnTheScreen();

    await swipeTo(1);
    expect(screen.queryByTestId('clip-carousel-sound')).toBeNull();
    expect(screen.getByTestId('clip-carousel-no-sound')).toBeOnTheScreen();

    await swipeTo(2);
    // A photo has no sound to turn on.
    expect(screen.queryByTestId('clip-carousel-sound')).toBeNull();
    expect(screen.queryByTestId('clip-carousel-no-sound')).toBeNull();
  });

  it('leaves the sound button to the clip for a post of one clip', async () => {
    mockPlayerProps.mockClear();
    await show(postWith([ready('c1')]));
    expect(screen.queryByTestId('clip-carousel-sound')).toBeNull();
    expect(mockPlayerProps).not.toHaveBeenCalledWith(
      expect.objectContaining({ soundButton: false }),
    );
  });

  it("jumps to an act's first clip when its chip is tapped", async () => {
    await show(postWith([ready('c1', cards), ready('c2', keo), ready('c3', keo)]));

    await fireEvent.press(screen.getByTestId('act-chip-a-keo'));
    expect(screen.getByTestId('clip-counter').props.children).toEqual([2, '/', 3]);
    expect(screen.getByTestId('player-c2-active')).toBeTruthy();
  });

  it('shows the act bar when the clips are of one act', async () => {
    await show(postWith([ready('c1', keo), ready('c2', keo)]));
    expect(screen.getByTestId('act-bar')).toBeOnTheScreen();
    expect(screen.getByTestId('act-chip-a-keo')).toBeOnTheScreen();
    expect(screen.getByTestId('clip-counter')).toBeTruthy();
  });

  it('puts the act chips beside the counter, on one line', async () => {
    await show(postWith([ready('c1', keo), ready('c2', keo)]));
    const row = screen.getByTestId('clip-where');
    expect(within(row).getByTestId('clip-counter')).toBeTruthy();
    expect(within(row).getByTestId('act-bar')).toBeTruthy();
  });

  it('has no act bar when no clip has an act', async () => {
    await show(postWith([ready('c1'), ready('c2')]));
    expect(screen.queryByTestId('act-bar')).toBeNull();
  });

  it('starts on the clip it was opened at, and plays nothing while the post is not in view', async () => {
    await show(postWith([ready('c1'), ready('c2'), ready('c3')]), {
      initialClipId: 'c3',
      active: false,
    });
    expect(screen.getByTestId('clip-counter').props.children).toEqual([3, '/', 3]);
    expect(screen.queryByTestId(/active$/)).toBeNull();
  });

  it("says who filmed a collaborator's clip", async () => {
    await show(postWith([ready('c1'), ready('c2', undefined, friend)]));
    expect(screen.queryByTestId('clip-filmed-by')).toBeNull();
    await swipeTo(1);
    expect(screen.getByTestId('clip-filmed-by').props.children).toBe('filmed by @sam');
  });

  it('shows a clip still on its way as its blurred preview, with the hint only for its owner', async () => {
    const waiting = {
      id: 'c2',
      position: 1,
      owner: author,
      status: 'WAITING',
      playback: { status: 'AWAITING_UPLOAD' },
      previewUrl: 'http://media/preview.jpg',
    } as PostClip;
    await show(postWith([ready('c1'), waiting]));
    await swipeTo(1);
    expect(screen.getByTestId('clip-on-its-way')).toBeTruthy();
    expect(screen.getByTestId('clip-preview').props.blurRadius).toBeGreaterThan(0);
    expect(screen.getByText('On its way')).toBeTruthy();
    expect(screen.queryByText(/Usually under a minute/)).toBeNull();

    useAuthStore.setState({ me: { id: author.id } } as never);
    await show(postWith([ready('c1'), waiting]));
    await swipeTo(1);
    expect(screen.getByText(/Usually under a minute/)).toBeTruthy();
  });
});
