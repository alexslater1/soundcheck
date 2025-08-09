import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react-native';

import { useAuthStore } from '@/features/auth/store';
import { fireEvent } from '@/test/fireEvent';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { CreateScreen } from './CreateScreen';
import type { PickResult } from './pickVideo';
import { testDraft } from './testDrafts';
import type { PickedVideo } from './types';
import { useUploadQueue } from './uploadQueue';
import type { PostInput } from './uploadRunner';

const mockPick = jest.fn<Promise<PickResult>, []>();
const mockStartUpload = jest.fn(async (_localId: string) => undefined);
const mockMakePreview = jest.fn(async (_localId: string) => undefined);
const mockResume = jest.fn();
const mockSubmit = jest.fn(async (_input: PostInput) => undefined);
const mockRetry = jest.fn(async (_localId: string) => undefined);
const mockPush = jest.fn();
const mockSetParams = jest.fn();
const mockRemove = jest.fn(async (localId: string) => useUploadQueue.getState().remove(localId));
const mockPrune = jest.fn();
const mockAttend = jest.fn();
const mockParams: { gigId?: string; postId?: string } = {};
const mockPost = jest.fn();
const mockGig = jest.fn();

const festival = {
  id: 'fest',
  title: 'Green Man Festival',
  startsAt: '2026-08-01T11:00:00Z',
  headliner: { id: 'fest-h', name: 'Big Act' },
  support: [],
  venue: { id: 'v', name: 'Glanusk Park' },
};
const paleKites = {
  id: 'gig-1',
  title: 'Pale Kites',
  startsAt: '2026-09-30T19:00:00Z',
  headliner: { id: 'head', name: 'Pale Kites' },
  support: [{ id: 'opener', name: 'Opener' }],
  venue: { id: 'v', name: 'Band on the Wall' },
};

// A gig still to come (reached by search before the picker stopped at today, or by a link).
const mockNextWeek = {
  ...paleKites,
  id: 'gig-2',
  startsAt: new Date(Date.now() + 7 * 24 * 3600 * 1000).toISOString(),
};

const mockAssumedWent = { ...paleKites, id: 'gig-3', myAttendance: 'WENT', wentUnconfirmed: true };
const mockSaidWent = { ...paleKites, id: 'gig-4', myAttendance: 'WENT', wentUnconfirmed: false };

jest.mock('./pickVideo', () => ({
  pickFromLibrary: () => mockPick(),
  recordWithCamera: () => mockPick(),
}));
jest.mock('./uploadRunner', () => ({
  startUpload: (localId: string) => mockStartUpload(localId),
  makePreview: (localId: string) => mockMakePreview(localId),
  resumeProcessing: (ownerId: string) => mockResume(ownerId),
  removeUpload: (localId: string) => mockRemove(localId),
  pruneFinished: (ownerId: string) => mockPrune(ownerId),
  submitPost: (input: PostInput) => mockSubmit(input),
  retryPost: (localId: string) => mockRetry(localId),
}));
jest.mock('@/api/generated/catalog/catalog', () => ({
  useGetGig: (...args: unknown[]) => mockGig(...args),
  useSetGigAttendance: () => ({ mutateAsync: mockAttend, isPending: false }),
  getListGigsToPostQueryKey: () => ['/api/v1/me/gigs/to-post'],
  useListArtists: () => ({ data: undefined }),
}));
jest.mock('@/api/generated/posts/posts', () => ({
  useGetPost: (...args: unknown[]) => mockPost(...args),
  getListUnpostedGigsQueryKey: () => ['/api/v1/me/gigs/unposted'],
}));
// The Pending list has its own data; here it offers one gig.
jest.mock('./PendingGigs', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    PendingGigs: ({ onPick }: { onPick: (gig: unknown) => void }) =>
      React.createElement(Text, { onPress: () => onPick(paleKites) }, 'pending-stub'),
    useMarkPendingGigsSeen: () => undefined,
  };
});
// The compose form has its own test; here it is a button that posts when it may, showing how many
// clips it holds and why it cannot post yet.
jest.mock('./ComposeForm', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    ComposeForm: ({
      clips,
      hint,
      joining,
      onPost,
    }: {
      clips: number;
      hint: string | null;
      joining: boolean;
      onPost: (result: unknown) => void;
    }) =>
      React.createElement(
        Text,
        {
          testID: 'compose-stub',
          onPress: () => (hint ? undefined : onPost({ caption: 'Encore', taggedUserIds: ['u9'] })),
        },
        `compose ${clips} ${joining ? 'joining' : 'new'} ${hint ?? 'ready'}`,
      ),
  };
});
// The gig picker has its own test; here one tap chooses a fixed gig (or a festival), and a chosen gig
// can be changed.
jest.mock('./GigPicker', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { Text, View } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    GigPicker: ({
      value,
      onChange,
    }: {
      value: { title: string } | null;
      onChange: (gig: unknown) => void;
    }) =>
      value
        ? React.createElement(Text, { onPress: () => onChange(null) }, `gig: ${value.title}`)
        : React.createElement(
            View,
            null,
            React.createElement(Text, { onPress: () => onChange(paleKites) }, 'pick-gig-stub'),
            React.createElement(Text, { onPress: () => onChange(festival) }, 'pick-festival-stub'),
            React.createElement(
              Text,
              { onPress: () => onChange(mockNextWeek) },
              'pick-future-stub',
            ),
            React.createElement(
              Text,
              { onPress: () => onChange(mockAssumedWent) },
              'pick-assumed-stub',
            ),
            React.createElement(Text, { onPress: () => onChange(mockSaidWent) }, 'pick-went-stub'),
          ),
  };
});
// Playing a local file needs the native player; here a preview is a labelled box.
jest.mock('./ClipPreview', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    ClipPreview: ({ uri }: { uri: string }) => React.createElement(Text, null, `preview ${uri}`),
  };
});
jest.mock('./PhotoPreview', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    PhotoPreview: ({ uri }: { uri: string }) => React.createElement(Text, null, `photo ${uri}`),
  };
});
// Recording and its review have their own test; here the button hands the camera's result straight on.
jest.mock('./RecordClipButton', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    RecordClipButton: ({ onPick }: { onPick: (pick: () => Promise<PickResult>) => void }) =>
      React.createElement(Text, { onPress: () => onPick(() => mockPick()) }, 'Record a clip'),
  };
});
jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
jest.mock('expo-router', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  return {
    Link: ({ children }: { children: React.ReactNode }) =>
      React.createElement(React.Fragment, null, children),
    useRouter: () => ({ push: mockPush, setParams: mockSetParams }),
    useLocalSearchParams: () => mockParams,
    useFocusEffect: (effect: () => void) => React.useEffect(effect, [effect]),
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
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <CreateScreen />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

async function pickClips(...videos: PickedVideo[]) {
  mockPick.mockResolvedValue({ kind: 'picked', videos });
  const before = mockStartUpload.mock.calls.length;
  await fireEvent.press(screen.getByTestId('choose-video'));
  await waitFor(() => expect(mockStartUpload).toHaveBeenCalledTimes(before + videos.length));
}

describe('CreateScreen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    delete mockParams.gigId;
    delete mockParams.postId;
    useUploadQueue.setState({ entries: [] });
    useAuthStore.setState({ me: { id: 'user-1' } as never });
    mockPost.mockReturnValue({ isPending: true });
    mockGig.mockReturnValue({ data: undefined });
  });

  it('starts with an empty list and resumes polling for uploads still being processed', async () => {
    await renderScreen();

    expect(screen.getByTestId('uploads-empty')).toBeTruthy();
    expect(mockResume).toHaveBeenCalledWith('user-1');
  });

  it('shows every picked clip before any upload or preview frame starts', async () => {
    await renderScreen();
    const shownAtStart: boolean[] = [];
    mockStartUpload.mockImplementation((localId: string) => {
      const entry = useUploadQueue.getState().entries.find((e) => e.localId === localId);
      shownAtStart.push(screen.queryByText(`preview ${entry?.previewUri ?? ''}`) !== null);
      return Promise.resolve(undefined);
    });
    await pickClips(video, { ...video, uri: 'file:///b.mp4' });
    expect(shownAtStart).toEqual([true, true]);
    mockStartUpload.mockReset();
  });

  it('says it is getting the clips while the picker is still copying them', async () => {
    await renderScreen();
    let hand: (result: PickResult) => void = () => undefined;
    mockPick.mockReturnValueOnce(new Promise<PickResult>((resolve) => (hand = resolve)));
    await fireEvent.press(screen.getByTestId('choose-video'));
    expect(screen.getByTestId('clips-fetching')).toHaveTextContent('Getting your clips…');
    await act(async () => hand({ kind: 'picked', videos: [video] }));
    await waitFor(() => expect(screen.queryByTestId('clips-fetching')).toBeNull());
  });

  it('takes the gig and the clips in either order; Post says what is missing until both are set', async () => {
    await renderScreen();
    expect(screen.getByTestId('choose-video')).toBeEnabled();
    expect(screen.getByTestId('compose-stub')).toHaveTextContent(
      'compose 0 new Choose the gig and add your clips to post.',
    );

    await pickClips(video);
    expect(screen.getByTestId('compose-stub')).toHaveTextContent(/compose 1 new Choose the gig/);

    await fireEvent.press(screen.getByText('pick-gig-stub'));
    expect(screen.getByTestId('compose-stub')).toHaveTextContent('compose 1 new ready');
  });

  it('ticks the last step once Post can be pressed, with the Pending list below it', async () => {
    await renderScreen();
    expect(screen.getByTestId('step-Caption and post-todo')).toBeTruthy();
    // The Pending list comes after the post being made.
    const text = JSON.stringify(screen.toJSON());
    expect(text.indexOf('compose-stub')).toBeLessThan(text.indexOf('pending-stub'));

    await pickClips(video);
    await fireEvent.press(screen.getByText('pick-gig-stub'));
    expect(screen.getByTestId('step-Caption and post-done')).toBeTruthy();
  });

  it('can go on without a gig, and back', async () => {
    await renderScreen();
    await pickClips(video);

    await fireEvent.press(screen.getByTestId('no-gig'));
    expect(screen.getByText(/Not from a gig/)).toBeTruthy();
    expect(screen.getByTestId('compose-stub')).toHaveTextContent(/ready$/);

    await fireEvent.press(screen.getByText('Choose a gig'));
    expect(screen.getByTestId('compose-stub')).toHaveTextContent(/Choose the gig/);
  });

  it('queues picked clips at once, makes their preview frames, and previews them', async () => {
    await renderScreen();
    await fireEvent.press(screen.getByText('pick-gig-stub'));

    await pickClips(video);

    const [entry] = useUploadQueue.getState().entries;
    expect(entry).toMatchObject({
      ownerId: 'user-1',
      fileUri: 'file:///DCIM/clip.mp4', // the original: the queue decides about compressing
      previewUri: 'file:///DCIM/clip.mp4',
      prepared: false,
      state: 'PREPARING',
      temporary: false,
    });
    expect(mockStartUpload).toHaveBeenCalledWith(entry!.localId);
    expect(mockMakePreview).toHaveBeenCalledWith(entry!.localId);
    expect(screen.getByText('preview file:///DCIM/clip.mp4')).toBeTruthy();
    // Its state under the preview, and in full in the uploads list below.
    expect(screen.getAllByText('Getting ready…')).toHaveLength(2);
  });

  it('queues a picked photo ready to send, with no compressing and no preview frame', async () => {
    const photo: PickedVideo = {
      uri: 'file:///DCIM/crowd.jpg',
      fileName: 'crowd.jpg',
      contentType: 'image/jpeg',
      bytes: 3 * 1024 * 1024,
      durationMs: null,
      width: 3000,
      height: 4000,
    };
    await renderScreen();
    await fireEvent.press(screen.getByText('pick-gig-stub'));

    await pickClips(photo, video);

    const [videoEntry, photoEntry] = useUploadQueue.getState().entries;
    expect(photoEntry).toMatchObject({
      prepared: true,
      state: 'QUEUED',
      contentType: 'image/jpeg',
    });
    expect(videoEntry).toMatchObject({ prepared: false, state: 'PREPARING' });
    expect(mockMakePreview).toHaveBeenCalledTimes(1);
    expect(mockMakePreview).toHaveBeenCalledWith(videoEntry!.localId);
    // The photo is previewed as a photo, the video as a clip; each carries an act like any clip.
    expect(screen.getByText('photo file:///DCIM/crowd.jpg')).toBeTruthy();
    expect(screen.getByText('preview file:///DCIM/clip.mp4')).toBeTruthy();
    expect(screen.getByTestId(`act-${photoEntry!.localId}`)).toHaveTextContent('Pale Kites');
  });

  it('refuses a photo the server would not keep (HEIC, over 10 MB) without uploading it', async () => {
    const heic = {
      ...video,
      fileName: 'a.heic',
      contentType: 'image/heic',
      bytes: 1000,
      durationMs: null,
    };
    const huge = { ...heic, fileName: 'b.jpg', contentType: 'image/jpeg', bytes: 11 * 1024 * 1024 };
    mockPick.mockResolvedValue({ kind: 'picked', videos: [heic, huge] });
    await renderScreen();

    await fireEvent.press(screen.getByText('Choose videos or photos'));

    await waitFor(() =>
      expect(screen.getByTestId('create-problem').props.children).toBe(
        'a.heic: Choose a JPEG, PNG or WebP photo.\nb.jpg: Photos can be up to 10 MB.',
      ),
    );
    expect(mockStartUpload).not.toHaveBeenCalled();
  });

  it('Post makes one post of the clips in running order, each with its act and song, then offers more from the gig', async () => {
    const second = { ...video, uri: 'file:///DCIM/clip2.mp4', fileName: 'clip2.mp4' };
    await renderScreen();
    await fireEvent.press(screen.getByText('pick-gig-stub'));
    await pickClips(video, second);
    const [secondId, firstId] = useUploadQueue.getState().entries.map((entry) => entry.localId);

    // Each clip starts as the headliner's; the second is the opener's.
    expect(screen.getByTestId(`act-${firstId}`)).toHaveTextContent('Pale Kites');
    await fireEvent.press(screen.getByTestId(`act-${secondId}`));
    await fireEvent.press(screen.getByText('Opener'));
    expect(screen.getByTestId(`act-${secondId}`)).toHaveTextContent('Opener');
    // A song typed for one clip goes with it.
    await fireEvent.changeText(screen.getByTestId(`song-${firstId}`), 'Bloodhound');

    await fireEvent.press(screen.getByTestId('compose-stub'));

    expect(mockSubmit).toHaveBeenCalledTimes(1);
    expect(mockSubmit).toHaveBeenCalledWith({
      caption: 'Encore',
      gigId: 'gig-1',
      day: null,
      taggedUserIds: ['u9'],
      joinPostId: null,
      // The opener plays first.
      clips: [
        { localId: secondId, artistId: 'opener', song: null },
        { localId: firstId, artistId: 'head', song: 'Bloodhound' },
      ],
    });
    expect(mockPush).toHaveBeenCalledWith('/me');

    await fireEvent.press(screen.getByText('Add more clips from Pale Kites'));
    expect(screen.getByText('gig: Pale Kites')).toBeTruthy();
  });

  it('a clip can have no act, and more than ten of one act stops Post', async () => {
    await renderScreen();
    await fireEvent.press(screen.getByText('pick-gig-stub'));
    await pickClips(
      ...Array.from({ length: 11 }, (_, i) => ({ ...video, uri: `file:///c${i}.mp4` })),
    );

    expect(screen.getByTestId('act-limit')).toBeTruthy();
    expect(screen.getByTestId('compose-stub')).toHaveTextContent(/10 of your clips/);

    const one = useUploadQueue.getState().entries[0]!.localId;
    await fireEvent.press(screen.getByTestId(`act-${one}`));
    await fireEvent.press(screen.getByText('No act'));
    expect(screen.queryByTestId('act-limit')).toBeNull();
    expect(screen.getByTestId('compose-stub')).toHaveTextContent(/ready$/);
  });

  it('asks which day of a festival the clips are from', async () => {
    await renderScreen();
    await fireEvent.press(screen.getByText('pick-festival-stub'));
    await pickClips(video);

    expect(screen.getByTestId('festival-days')).toBeTruthy();
    await fireEvent.press(screen.getByText('Day 2 · Sun 2 Aug'));
    await fireEvent.press(screen.getByTestId('compose-stub'));

    expect(mockSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ gigId: 'fest', day: '2026-08-02' }),
    );
  });

  it('"I was there" marks the gig Went without posting', async () => {
    mockAttend.mockResolvedValue({ myAttendance: 'WENT' });
    await renderScreen();
    await fireEvent.press(screen.getByText('pick-gig-stub'));

    await fireEvent.press(screen.getByTestId('was-there'));

    expect(mockAttend).toHaveBeenCalledWith({ id: 'gig-1', data: { status: 'WENT' } });
    expect(await screen.findByTestId('was-there-done')).toHaveTextContent(/Pending list/);
    expect(mockSubmit).not.toHaveBeenCalled();
  });

  it('offers no "I was there" for a gig whose day has not come', async () => {
    await renderScreen();
    await fireEvent.press(screen.getByText('pick-future-stub'));
    expect(screen.queryByTestId('was-there')).toBeNull();
    expect(screen.queryByTestId('went-already')).toBeNull();
  });

  it('asks again about a Went assumed from a past Going', async () => {
    await renderScreen();
    await fireEvent.press(screen.getByText('pick-assumed-stub'));
    expect(screen.getByTestId('was-there')).toBeOnTheScreen();
  });

  it('does not ask about a Went the fan said', async () => {
    await renderScreen();
    await fireEvent.press(screen.getByText('pick-went-stub'));
    expect(screen.queryByTestId('was-there')).toBeNull();
    expect(screen.getByTestId('went-already')).toBeOnTheScreen();
  });

  it('a pending gig, or one picked on Me, becomes the post’s gig', async () => {
    await renderScreen();
    await fireEvent.press(screen.getByText('pending-stub'));
    expect(screen.getByText('gig: Pale Kites')).toBeTruthy();
    expect(screen.queryByText('pending-stub')).toBeNull();
  });

  it('a gigId param from Me’s Pending list preselects the gig', async () => {
    mockParams.gigId = 'gig-1';
    mockGig.mockReturnValue({ data: { gig: paleKites } });
    await renderScreen();
    expect(mockGig).toHaveBeenCalledWith('gig-1', undefined, { query: { enabled: true } });
    expect(screen.getByText('gig: Pale Kites')).toBeTruthy();
  });

  it('adds clips to an existing post with a postId param, and goes back to it', async () => {
    mockParams.postId = 'post-9';
    mockParams.gigId = 'gig-1';
    mockPost.mockReturnValue({
      isPending: false,
      data: { id: 'post-9', author: { id: 'sam', handle: 'sam' }, gig: paleKites },
    });
    await renderScreen();

    expect(screen.getByTestId('joining')).toHaveTextContent(
      "Adding to @sam's post from Pale Kites",
    );
    expect(screen.queryByText('pick-gig-stub')).toBeNull();
    await pickClips(video);
    expect(screen.getByTestId('compose-stub')).toHaveTextContent('compose 1 joining ready');

    await fireEvent.press(screen.getByTestId('compose-stub'));

    expect(mockSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ joinPostId: 'post-9', gigId: null }),
    );
    expect(mockSetParams).toHaveBeenCalledWith({ postId: undefined, gigId: undefined });
    expect(mockPush).toHaveBeenCalledWith({ pathname: '/post/[id]', params: { id: 'post-9' } });
  });

  it('removes a clip from the post (and its upload) with its cross', async () => {
    await renderScreen();
    await pickClips(video);
    const localId = useUploadQueue.getState().entries[0]!.localId;

    await fireEvent.press(screen.getByLabelText('Remove clip.mp4'));

    expect(mockRemove).toHaveBeenCalledWith(localId);
    expect(screen.getByTestId('compose-stub')).toHaveTextContent(/^compose 0 /);
  });

  it('offers Post again when a post request failed, and Write post for clips from On their way', async () => {
    const draft = testDraft({ caption: 'Encore' });
    const unposted = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    const one = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    const two = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    useUploadQueue.getState().patch(unposted, {
      state: 'READY',
      mediaId: 'media-1',
      draft,
      postError: 'Network request failed',
    });
    useUploadQueue.getState().patch(one, { state: 'READY', mediaId: 'media-2' });
    useUploadQueue.getState().patch(two, { state: 'READY', mediaId: 'media-3' });
    await renderScreen();

    expect(screen.getByTestId(`upload-${unposted}-post`).props.children).toBe(
      'Not posted: Network request failed',
    );
    await fireEvent.press(screen.getByText('Post again'));
    expect(mockRetry).toHaveBeenCalledWith(unposted);

    // Two clips from the list join one post.
    await fireEvent.press(screen.getAllByText('Write post')[0]!);
    await fireEvent.press(screen.getAllByText('Write post')[0]!);
    await fireEvent.press(screen.getByTestId('no-gig'));
    await fireEvent.press(screen.getByTestId('compose-stub'));
    expect(mockSubmit).toHaveBeenCalledTimes(1);
    expect(mockSubmit.mock.calls[0]![0].clips.map((c) => c.localId).sort()).toEqual(
      [one, two].sort(),
    );
  });

  it('refuses a clip that is too long without uploading anything', async () => {
    mockPick.mockResolvedValue({ kind: 'picked', videos: [{ ...video, durationMs: 195_000 }] });
    await renderScreen();

    await fireEvent.press(screen.getByText('Choose videos or photos'));

    expect(await screen.findByText('Clips can be up to 3 minutes long.')).toBeTruthy();
    expect(mockStartUpload).not.toHaveBeenCalled();
    expect(useUploadQueue.getState().entries).toHaveLength(0);
  });

  it('posts the clips that can be posted and names the ones that cannot', async () => {
    const tooLong = { ...video, fileName: 'long.mp4', durationMs: 195_000 };
    mockPick.mockResolvedValue({ kind: 'picked', videos: [video, tooLong] });
    await renderScreen();

    await fireEvent.press(screen.getByText('Choose videos or photos'));

    await waitFor(() => expect(mockStartUpload).toHaveBeenCalledTimes(1));
    expect(screen.getByTestId('create-problem').props.children).toBe(
      'long.mp4: Clips can be up to 3 minutes long.',
    );
  });

  it('does nothing when the picker is cancelled, and explains a denied camera permission', async () => {
    mockPick.mockResolvedValueOnce({ kind: 'cancelled' });
    await renderScreen();
    await fireEvent.press(screen.getByText('Choose videos or photos'));
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
