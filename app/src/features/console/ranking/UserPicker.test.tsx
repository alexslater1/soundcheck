import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen } from '@testing-library/react-native';

import type { AdminListUsersParams, AdminUser, PageAdminUser } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { UserPicker, type PickedUser } from './UserPicker';

const mockCalls: AdminListUsersParams[] = [];
let mockAnswer: (params: AdminListUsersParams) => PageAdminUser;

jest.mock('@/api/generated/admin-users/admin-users', () => ({
  getAdminListUsersQueryKey: (params?: object) => [
    '/api/v1/admin/users',
    ...(params ? [params] : []),
  ],
  adminListUsers: async (params: AdminListUsersParams) => {
    mockCalls.push(params);
    return mockAnswer(params);
  },
}));

function user(handle: string, synthetic: boolean): AdminUser {
  return {
    id: `id-${handle}`,
    handle,
    displayName: handle.toUpperCase(),
    email: `${handle}@example.com`,
    role: 'USER',
    posts: 0,
    createdAt: '2026-10-01T00:00:00Z',
    synthetic,
  };
}

async function renderPicker(selected: PickedUser | null = null, onPick = jest.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  await render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <UserPicker selected={selected} onPick={onPick} />
      </ThemeProvider>
    </QueryClientProvider>,
  );
  return onPick;
}

describe('UserPicker', () => {
  beforeEach(() => {
    mockCalls.length = 0;
    mockAnswer = (params) => {
      if (params.q) return { items: [user('maya_frontrow', true)], nextCursor: null };
      return params.synthetic
        ? { items: [user('tariq_metal', true), user('will_classical', true)], nextCursor: null }
        : { items: [user('alex1223', false)], nextCursor: 'next' };
    };
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('lists the personas, then the other accounts, without a search', async () => {
    await renderPicker();
    expect(await screen.findByTestId('user-picker-option-tariq_metal')).toBeOnTheScreen();
    expect(screen.getByTestId('user-picker-option-will_classical')).toBeOnTheScreen();
    expect(await screen.findByTestId('user-picker-option-alex1223')).toBeOnTheScreen();
    expect(screen.getByText('More accounts')).toBeOnTheScreen();
    expect(screen.getByText('nobody yet: pick an account below')).toBeOnTheScreen();
  });

  it('picks an account, and highlights the current one with no "Keep" link', async () => {
    const onPick = await renderPicker({
      id: 'id-tariq_metal',
      handle: 'tariq_metal',
      synthetic: true,
    });
    expect(screen.getByTestId('user-picker-selected')).toHaveTextContent('@tariq_metal');
    expect(await screen.findByTestId('user-picker-option-tariq_metal')).toBeSelected();
    expect(screen.getByTestId('user-picker-option-will_classical')).not.toBeSelected();
    expect(screen.queryByText(/Keep @/)).toBeNull();
    await fireEvent.press(screen.getByTestId('user-picker-option-will_classical'));
    expect(onPick).toHaveBeenCalledWith({
      id: 'id-will_classical',
      handle: 'will_classical',
      synthetic: true,
    });
  });

  it('filters to one list of matches once typing stops', async () => {
    jest.useFakeTimers();
    await renderPicker();
    await fireEvent.changeText(screen.getByTestId('user-picker-search'), 'maya');
    await act(async () => {
      jest.advanceTimersByTime(300);
    });
    expect(await screen.findByTestId('user-picker-option-maya_frontrow')).toBeOnTheScreen();
    expect(screen.queryByText('OTHER ACCOUNTS')).toBeNull();
    expect(mockCalls.at(-1)).toEqual({ q: 'maya', limit: 50 });
  });
});
