import { renderHook, waitFor } from '@testing-library/react-native';

import { useAuthStore } from '@/features/auth/store';

import type { PickResult } from './pickVideo';
import { takeRecovered, useRecoveredRecording } from './recoveredRecording';

const mockPush = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush }) }));
const mockPending = jest.fn<Promise<PickResult | null>, []>();
jest.mock('./pickVideo', () => ({ pendingRecording: () => mockPending() }));

const recording: PickResult = {
  kind: 'picked',
  videos: [
    {
      uri: 'file:///cache/ImagePicker/clip.mp4',
      fileName: 'clip.mp4',
      contentType: 'video/mp4',
      bytes: 5_400_000,
      durationMs: 4_000,
      width: 1080,
      height: 1920,
    },
  ],
};

const signIn = () =>
  useAuthStore.setState({
    status: 'signedIn',
    me: { id: 'u1', onboarded: true } as never,
  });

describe('useRecoveredRecording (Android killed the app during the camera)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    takeRecovered();
  });

  it('opens Create with the recording the camera kept, once', async () => {
    mockPending.mockResolvedValue(recording);
    signIn();
    const { unmount } = await renderHook(() => useRecoveredRecording());

    await waitFor(() => expect(mockPush).toHaveBeenCalledWith('/create'));
    expect(takeRecovered()).toEqual(recording);
    expect(takeRecovered()).toBeNull();
    await unmount();
  });

  it('does nothing when no recording is waiting, or before anyone is signed in', async () => {
    mockPending.mockResolvedValue(null);
    signIn();
    const first = await renderHook(() => useRecoveredRecording());
    await waitFor(() => expect(mockPending).toHaveBeenCalled());
    expect(mockPush).not.toHaveBeenCalled();
    await first.unmount();

    jest.clearAllMocks();
    useAuthStore.setState({ status: 'signedOut', me: null });
    const second = await renderHook(() => useRecoveredRecording());
    expect(mockPending).not.toHaveBeenCalled();
    await second.unmount();
  });
});
