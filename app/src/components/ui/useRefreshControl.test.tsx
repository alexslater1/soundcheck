import { act, render, screen } from '@testing-library/react-native';
import { ScrollView } from 'react-native';

import { MIN_REFRESH_MS } from '@/features/feed/usePullToRefresh';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { useRefreshControl } from './useRefreshControl';

function List({ refresh }: { refresh: () => Promise<unknown> }) {
  return <ScrollView testID="list" refreshControl={useRefreshControl(refresh)} />;
}

const control = () =>
  screen.getByTestId('list').props.refreshControl as {
    props: { refreshing: boolean; onRefresh: () => void };
  };

describe('useRefreshControl', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it('spins while refreshing, for at least MIN_REFRESH_MS, then stops', async () => {
    const refresh = jest.fn(() => Promise.resolve());
    await render(
      <ThemeProvider>
        <List refresh={refresh} />
      </ThemeProvider>,
    );
    expect(control().props.refreshing).toBe(false);

    await act(async () => control().props.onRefresh());
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(control().props.refreshing).toBe(true); // the answer came at once; the spinner stays

    await act(async () => jest.advanceTimersByTime(MIN_REFRESH_MS));
    expect(control().props.refreshing).toBe(false);
  });

  it('stops spinning when the refresh fails', async () => {
    const refresh = jest.fn(() => Promise.reject(new Error('offline')));
    await render(
      <ThemeProvider>
        <List refresh={refresh} />
      </ThemeProvider>,
    );
    await act(async () => control().props.onRefresh());
    await act(async () => jest.advanceTimersByTime(MIN_REFRESH_MS));
    expect(control().props.refreshing).toBe(false);
  });
});
