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
const mockSubmit = jest.fn(async (_localId: string, _draft: unknown) => undefined);
const mockPush = jest.fn();
const mockRemove = jest.fn(async (localId: string) => useUploadQueue.getState().remove(localId));
const mockPrune = jest.fn();

jest.mock('./pickVideo', () => ({
  pickFromLibrary: () => mockPick(),
  recordWithCamera: () => mockPick(),
}));
jest.mock('./uploadRunner', () => ({
  startUpload: (localId: string) => mockStartUpload(localId),
  resumeProcessing: (ownerId: string) => mockResume(ownerId),
  removeUpload: (localId: string) => mockRemove(localId),
  pruneFinished: (ownerId: string) => mockPrune(ownerId),
  submitPost: (localId: string, draft: unknown) => mockSubmit(localId, draft),
}));
// The compose form has its own test; here it is a button that posts a fixed draft, showing how many
// clips it was opened for and the gig it was given.
jest.mock('./ComposeForm', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    ComposeForm: ({
      clips,
      gig,
      onPost,
    }: {
      clips: number;
      gig: { title: string } | null;
      onPost: (draft: unknown) => void;
    }) =>
      React.createElement(
        Text,
        { onPress: () => onPost({ caption: 'Encore', gigId: 'gig-1', artistIds: ['artist-1'] }) },
        `compose-stub ${clips} ${gig?.title ?? 'no-gig'}`,
      ),
  };
});
// The gig picker has its own test; here one tap chooses a fixed gig, and a chosen gig can be changed.
jest.mock('./GigPicker', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
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
            Text,
            { onPress: () => onChange({ id: 'gig-1', title: 'Pale Kites' }) },
            'pick-gig-stub',
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
jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
jest.mock('expo-router', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  return {
    Link: ({ children }: { children: React.ReactNode }) =>
      React.createElement(React.Fragment, null, children),
    useRouter: () => ({ push: mockPush }),
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
  });

  it('starts with an empty list and resumes polling for uploads still being processed', async () => {
    await renderScreen();

    expect(screen.getByTestId('uploads-empty')).toBeTruthy();
    expect(mockResume).toHaveBeenCalledWith('user-1');
  });

  it('asks for the gig first: the clips step opens once a gig is chosen (G18)', async () => {
    await renderScreen();
    expect(screen.getByTestId('choose-video')).toBeDisabled();

    await fireEvent.press(screen.getByText('pick-gig-stub'));

    expect(screen.getByTestId('choose-video')).toBeEnabled();
    expect(screen.getByText('gig: Pale Kites')).toBeTruthy();
  });

  it('can go on without a gig, and back', async () => {
    await renderScreen();

    await fireEvent.press(screen.getByTestId('no-gig'));
    expect(screen.getByTestId('choose-video')).toBeEnabled();
    expect(screen.getByText(/Not from a gig/)).toBeTruthy();

    await fireEvent.press(screen.getByText('Choose a gig'));
    expect(screen.getByTestId('choose-video')).toBeDisabled();
  });

  it('queues picked clips at once to be prepared in the background, and previews them (G19, G20)', async () => {
    mockPick.mockResolvedValue({ kind: 'picked', videos: [video] });
    await renderScreen();
    await fireEvent.press(screen.getByText('pick-gig-stub'));

    await fireEvent.press(screen.getByText('Choose videos'));

    await waitFor(() => expect(mockStartUpload).toHaveBeenCalledTimes(1));
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
    expect(screen.getByText('preview file:///DCIM/clip.mp4')).toBeTruthy();
    // Its state under the preview, and in full in the uploads list below.
    expect(screen.getAllByText('Getting ready…')).toHaveLength(2);
    expect(screen.getByText('compose-stub 1 Pale Kites')).toBeTruthy();
  });

  it('Post hands the draft over for each clip, returns to the profile, and offers more from the gig', async () => {
    const second = { ...video, uri: 'file:///DCIM/clip2.mp4', fileName: 'clip2.mp4' };
    mockPick.mockResolvedValue({ kind: 'picked', videos: [video, second] });
    await renderScreen();
    await fireEvent.press(screen.getByText('pick-gig-stub'));
    await fireEvent.press(screen.getByText('Choose videos'));
    await waitFor(() => expect(mockStartUpload).toHaveBeenCalledTimes(2));
    const ids = useUploadQueue.getState().entries.map((entry) => entry.localId);

    await fireEvent.press(await screen.findByText('compose-stub 2 Pale Kites'));

    const draft = { caption: 'Encore', gigId: 'gig-1', artistIds: ['artist-1'] };
    expect(mockSubmit.mock.calls.map(([id]) => id).sort()).toEqual([...ids].sort());
    expect(
      mockSubmit.mock.calls.every(([, sent]) => JSON.stringify(sent) === JSON.stringify(draft)),
    ).toBe(true);
    expect(mockPush).toHaveBeenCalledWith('/me');
    expect(screen.queryByText(/compose-stub/)).toBeNull();
    expect(screen.getByTestId('choose-video')).toBeDisabled(); // a new post starts at the gig again

    await fireEvent.press(screen.getByText('Add more clips from Pale Kites'));
    expect(screen.getByText('gig: Pale Kites')).toBeTruthy();
    expect(screen.getByTestId('choose-video')).toBeEnabled();
  });

  it('removes a clip from the post (and its upload) with its cross', async () => {
    mockPick.mockResolvedValue({ kind: 'picked', videos: [video] });
    await renderScreen();
    await fireEvent.press(screen.getByText('pick-gig-stub'));
    await fireEvent.press(screen.getByText('Choose videos'));
    await waitFor(() => expect(mockStartUpload).toHaveBeenCalledTimes(1));
    const localId = useUploadQueue.getState().entries[0]!.localId;

    await fireEvent.press(screen.getByLabelText('Remove clip.mp4'));

    expect(mockRemove).toHaveBeenCalledWith(localId);
    expect(screen.queryByText(/compose-stub/)).toBeNull();
  });

  it('offers Post again when the post request failed, and Write post when nothing was written', async () => {
    const draft = { caption: 'Encore', gigId: null, artistIds: [] };
    const unposted = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    const unwritten = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    const rejected = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    useUploadQueue.getState().patch(unposted, {
      state: 'READY',
      mediaId: 'media-1',
      draft,
      postError: 'Network request failed',
    });
    useUploadQueue.getState().patch(unwritten, { state: 'READY', mediaId: 'media-2' });
    useUploadQueue.getState().patch(rejected, { state: 'REJECTED', mediaId: 'media-3', draft });
    await renderScreen();

    expect(screen.getByTestId(`upload-${unposted}-post`).props.children).toBe(
      'Not posted: Network request failed',
    );
    expect(screen.getByTestId(`upload-${rejected}-post`).props.children).toBe(
      'Not posted: this clip could not be used.',
    );
    // One of each: the rejected clip can neither be posted again nor written for.
    await fireEvent.press(screen.getByText('Post again'));
    expect(mockSubmit).toHaveBeenCalledWith(unposted, draft);

    expect(screen.queryByText(/compose-stub/)).toBeNull();
    await fireEvent.press(screen.getByText('Write post'));
    await fireEvent.press(await screen.findByText(/compose-stub/));
    expect(mockSubmit).toHaveBeenLastCalledWith(unwritten, {
      caption: 'Encore',
      gigId: 'gig-1',
      artistIds: ['artist-1'],
    });
  });

  it('refuses a clip that is too long without uploading anything', async () => {
    mockPick.mockResolvedValue({ kind: 'picked', videos: [{ ...video, durationMs: 75_000 }] });
    await renderScreen();
    await fireEvent.press(screen.getByText('pick-gig-stub'));

    await fireEvent.press(screen.getByText('Choose videos'));

    expect(await screen.findByText('Clips can be up to 60 seconds long.')).toBeTruthy();
    expect(mockStartUpload).not.toHaveBeenCalled();
    expect(useUploadQueue.getState().entries).toHaveLength(0);
  });

  it('posts the clips that can be posted and names the ones that cannot', async () => {
    const tooLong = { ...video, fileName: 'long.mp4', durationMs: 75_000 };
    mockPick.mockResolvedValue({ kind: 'picked', videos: [video, tooLong] });
    await renderScreen();
    await fireEvent.press(screen.getByText('pick-gig-stub'));

    await fireEvent.press(screen.getByText('Choose videos'));

    await waitFor(() => expect(mockStartUpload).toHaveBeenCalledTimes(1));
    expect(screen.getByTestId('create-problem').props.children).toBe(
      'long.mp4: Clips can be up to 60 seconds long.',
    );
  });

  it('does nothing when the picker is cancelled, and explains a denied camera permission', async () => {
    mockPick.mockResolvedValueOnce({ kind: 'cancelled' });
    await renderScreen();
    await fireEvent.press(screen.getByText('pick-gig-stub'));
    await fireEvent.press(screen.getByText('Choose videos'));
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
