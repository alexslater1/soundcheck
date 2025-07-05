import { fireEvent, render, screen } from '@testing-library/react-native';
import { AppState, type AppStateStatus } from 'react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { UnreachableScreen } from './UnreachableScreen';

describe('UnreachableScreen', () => {
  it('says the user is still signed in and tries again on the button', async () => {
    const retry = jest.fn(async () => undefined);
    await render(
      <ThemeProvider>
        <UnreachableScreen onRetry={retry} />
      </ThemeProvider>,
    );

    expect(screen.getByText("Can't connect to Soundcheck")).toBeTruthy();
    expect(screen.getByText(/still signed in/)).toBeTruthy();
    fireEvent.press(screen.getByText('Try again'));
    expect(retry).toHaveBeenCalledTimes(1);
  });

  it('tries again by itself when the app comes back to the foreground', async () => {
    let listener: (state: AppStateStatus) => void = () => {};
    jest.spyOn(AppState, 'addEventListener').mockImplementation((_type, handler) => {
      listener = handler as (state: AppStateStatus) => void;
      return { remove: jest.fn() } as unknown as ReturnType<typeof AppState.addEventListener>;
    });
    const retry = jest.fn(async () => undefined);
    await render(
      <ThemeProvider>
        <UnreachableScreen onRetry={retry} />
      </ThemeProvider>,
    );

    listener('background');
    expect(retry).not.toHaveBeenCalled();
    listener('active');
    expect(retry).toHaveBeenCalledTimes(1);
  });
});
