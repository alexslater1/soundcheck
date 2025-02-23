import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import { completeOnboarding } from '@/api/generated/me/me';
import { ApiError } from '@/api/http';
import ArtistsStep from '@/app/onboarding/artists';
import { useAuthStore } from '@/features/auth/store';

import { saveErrorMessage } from './useSaveStep';

jest.mock('@/api/generated/me/me', () => ({ completeOnboarding: jest.fn() }));

const completeMock = completeOnboarding as jest.MockedFunction<typeof completeOnboarding>;
const reloadMe = jest.fn<Promise<void>, []>();

beforeEach(() => {
  completeMock.mockReset();
  reloadMe.mockReset().mockResolvedValue(undefined);
  useAuthStore.setState({ reloadMe });
});

describe('saveErrorMessage', () => {
  it('uses the Problem Details message, a network hint, or a generic fallback', () => {
    expect(saveErrorMessage(new ApiError(400, 'CITY_UNKNOWN', 'Unknown city id 7'))).toBe(
      'Unknown city id 7',
    );
    expect(saveErrorMessage(new ApiError(0, 'NETWORK_ERROR', 'Could not reach the server'))).toBe(
      'Could not reach the server. Check your connection and try again.',
    );
    expect(saveErrorMessage(new Error('boom'))).toBe('Something went wrong. Please try again.');
  });
});

describe('onboarding step error state (artists step)', () => {
  it('shows the error, keeps the user on the step, and lets them retry', async () => {
    completeMock.mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', 'offline'));
    await render(<ArtistsStep />);

    await fireEvent.press(screen.getByRole('button', { name: 'Finish' }));

    await waitFor(() =>
      expect(screen.getByTestId('step-error')).toHaveTextContent(
        'Could not reach the server. Check your connection and try again.',
      ),
    );
    expect(reloadMe).not.toHaveBeenCalled();

    // Retry succeeds: the error clears and /me is reloaded (the root layout then redirects).
    completeMock.mockResolvedValueOnce(undefined as never);
    await fireEvent.press(screen.getByRole('button', { name: 'Finish' }));
    await waitFor(() => expect(reloadMe).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('step-error')).toBeNull();
  });
});
