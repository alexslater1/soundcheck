import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import type { ReactNode } from 'react';

import type { AdminListUsersParams, AdminUser, PageAdminUser } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import UsersPage, { SEARCH_DEBOUNCE_MS } from './UsersPage';

const mockCalls: AdminListUsersParams[] = [];
let mockAnswer: (params: AdminListUsersParams) => Promise<PageAdminUser>;

jest.mock('@/api/generated/admin-users/admin-users', () => ({
  getAdminListUsersQueryKey: (params?: AdminListUsersParams) => [
    '/api/v1/admin/users',
    ...(params ? [params] : []),
  ],
  adminListUsers: (params: AdminListUsersParams) => {
    mockCalls.push(params);
    return mockAnswer(params);
  },
}));
jest.mock('expo-router', () => ({
  Link: ({ children }: { children: ReactNode }) => children,
}));

const user = (handle: string, synthetic: boolean): AdminUser => ({
  id: `id-${handle}`,
  handle,
  displayName: handle.toUpperCase(),
  email: `${handle}@${synthetic ? 'seed.soundcheck.test' : 'example.com'}`,
  role: 'USER',
  createdAt: '2026-10-01T10:00:00Z',
  synthetic,
  posts: 2,
});

async function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  await render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <UsersPage />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('UsersPage', () => {
  beforeEach(() => {
    mockCalls.length = 0;
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('lists accounts and tags the synthetic ones', async () => {
    mockAnswer = async () => ({
      items: [user('alice', false), user('persona', true)],
      nextCursor: null,
    });
    await renderPage();
    expect(await screen.findByText('@alice')).toBeOnTheScreen();
    expect(screen.getAllByTestId('synthetic-tag')).toHaveLength(1);
    expect(screen.getByText('persona@seed.soundcheck.test')).toBeOnTheScreen();
    expect(screen.queryByTestId('users-table-more')).toBeNull();
  });

  it('asks the server only once typing stops, and filters by kind', async () => {
    mockAnswer = async () => ({ items: [], nextCursor: null });
    await renderPage();
    await fireEvent.changeText(screen.getByTestId('users-search'), 'pers');
    await act(async () => {
      jest.advanceTimersByTime(SEARCH_DEBOUNCE_MS - 1);
    });
    expect(mockCalls.some((c) => c.q)).toBe(false);
    await act(async () => {
      jest.advanceTimersByTime(1);
    });
    expect(mockCalls.at(-1)).toEqual({ q: 'pers' });
    await fireEvent.press(screen.getByTestId('users-kind-synthetic'));
    expect(mockCalls.at(-1)).toEqual({ q: 'pers', synthetic: true });
    expect(await screen.findByText('No account matches "pers"')).toBeOnTheScreen();
  });

  it('loads the next page with the cursor', async () => {
    mockAnswer = async (p) =>
      p.cursor
        ? { items: [user('second', false)], nextCursor: null }
        : { items: [user('first', false)], nextCursor: 'c1' };
    await renderPage();
    await fireEvent.press(await screen.findByTestId('users-table-more'));
    expect(await screen.findByText('@second')).toBeOnTheScreen();
    expect(screen.getByText('@first')).toBeOnTheScreen();
    expect(mockCalls.at(-1)).toEqual({ cursor: 'c1' });
  });

  it('shows the error state', async () => {
    mockAnswer = async () => {
      throw new Error('500');
    };
    await renderPage();
    expect(await screen.findByText("Couldn't load this table")).toBeOnTheScreen();
  });
});
