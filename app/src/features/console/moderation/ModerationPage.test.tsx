import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';

import { ApiError } from '@/api/http';
import type {
  AdminListReportsParams,
  PageReportItem,
  ReportItem,
  ResolveRequest,
} from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import ModerationPage from './ModerationPage';

const mockCalls: AdminListReportsParams[] = [];
let mockAnswer: (params: AdminListReportsParams) => Promise<PageReportItem>;
const mockResolve = jest.fn(async (_id: string, _body: ResolveRequest) => ({}));
const mockRestore = jest.fn(async (_id: string) => undefined);

jest.mock('@/api/generated/admin-moderation/admin-moderation', () => ({
  getAdminListReportsQueryKey: (params?: AdminListReportsParams) => [
    '/api/v1/admin/reports',
    ...(params ? [params] : []),
  ],
  adminListReports: (params: AdminListReportsParams) => {
    mockCalls.push(params);
    return mockAnswer(params);
  },
  adminResolveReport: (id: string, body: ResolveRequest) => mockResolve(id, body),
  adminRestorePost: (id: string) => mockRestore(id),
}));
jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
// The player is tested on its own; here it only shows that Play swapped the poster for it.
jest.mock('@/features/player/VideoPlayer', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { View } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    VideoPlayer: (props: { hlsUrl: string }) =>
      React.createElement(View, { testID: 'report-player', accessibilityLabel: props.hlsUrl }),
  };
});

const postItem: ReportItem = {
  kind: 'POST',
  id: 'post-1',
  reportId: 'report-1',
  reportCount: 3,
  reasons: { NUDITY: 2, VIOLENCE: 1 },
  latestDetails: ['Not okay'],
  firstReportedAt: '2026-10-04T10:00:00Z',
  lastReportedAt: '2026-10-04T12:00:00Z',
  post: {
    id: 'post-1',
    status: 'PUBLISHED',
    caption: 'Front row',
    author: { id: 'a1', handle: 'maya', displayName: 'Maya' },
    gig: { id: 'g1', title: 'Keo at the Deaf Institute', startsAt: '2026-03-01T19:30:00Z' },
    playback: {
      status: 'READY',
      hlsUrl: 'http://localhost:8088/media/m/v1/master.m3u8',
      posterUrl: 'http://localhost:8088/media/m/v1/poster.jpg',
      kind: 'VIDEO',
    },
    clipCount: 1,
  },
};

const commentItem: ReportItem = {
  kind: 'GIG_COMMENT',
  id: 'comment-1',
  reportId: 'report-2',
  reportCount: 1,
  reasons: { ABUSE: 1 },
  latestDetails: [],
  firstReportedAt: '2026-10-04T11:00:00Z',
  lastReportedAt: '2026-10-04T11:00:00Z',
  comment: {
    id: 'comment-1',
    body: 'rubbish band',
    author: { id: 'u2', handle: 'troll', displayName: 'Troll' },
    createdAt: '2026-10-04T09:00:00Z',
    deleted: false,
    gig: { id: 'g1', title: 'Keo at the Deaf Institute', startsAt: '2026-03-01T19:30:00Z' },
  },
};

const hiddenItem: ReportItem = {
  ...postItem,
  post: { ...postItem.post!, status: 'HIDDEN' },
  resolution: {
    action: 'HIDE_POST',
    resolvedAt: '2026-10-04T13:00:00Z',
    resolvedBy: 'admin',
    targetStatus: 'HIDDEN',
  },
};

function answer(open: ReportItem[], resolved: ReportItem[]) {
  mockAnswer = async (params) => ({
    items: params.status === 'RESOLVED' ? resolved : open,
    nextCursor: null,
  });
}

async function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  await render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <ModerationPage />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('ModerationPage', () => {
  beforeEach(() => {
    mockCalls.length = 0;
    jest.clearAllMocks();
  });

  it('shows a post card and a comment card with their reasons, in context', async () => {
    answer([postItem, commentItem], []);
    await renderPage();

    const post = await screen.findByTestId('report-card-post-1');
    expect(within(post).getByText('3 reports')).toBeOnTheScreen();
    expect(within(post).getByText('Nudity ×2')).toBeOnTheScreen();
    expect(within(post).getByText('Violence')).toBeOnTheScreen();
    expect(within(post).getByText('“Not okay”')).toBeOnTheScreen();
    expect(within(post).getByText('Front row')).toBeOnTheScreen();
    expect(within(post).getByText('Status: PUBLISHED')).toBeOnTheScreen();
    expect(within(post).getByText('Hide post')).toBeOnTheScreen();

    const comment = screen.getByTestId('report-card-comment-1');
    expect(within(comment).getByText('rubbish band')).toBeOnTheScreen();
    expect(
      within(comment).getByText('On the gig Keo at the Deaf Institute · 2026-03-01'),
    ).toBeOnTheScreen();
    expect(within(comment).getByText('Delete comment')).toBeOnTheScreen();
    expect(within(comment).queryByText('Hide post')).toBeNull();
    expect(mockCalls[0]).toEqual({ status: 'OPEN', limit: 20 });
  });

  it('plays the reported clip when Play is pressed', async () => {
    answer([postItem], []);
    await renderPage();
    await screen.findByTestId('report-card-post-1');
    expect(screen.queryByTestId('report-player')).toBeNull();

    await fireEvent.press(screen.getByLabelText('Play'));

    expect(screen.getByTestId('report-player').props.accessibilityLabel).toBe(
      'http://localhost:8088/media/m/v1/master.m3u8',
    );
  });

  it('hides a post only after a confirmation that says what will change', async () => {
    answer([postItem], []);
    await renderPage();
    await screen.findByTestId('report-card-post-1');

    await fireEvent.press(screen.getByTestId('report-hide_post'));
    expect(screen.getByText('Hide post: post?')).toBeOnTheScreen();
    expect(screen.getByText('• Its 3 open reports are resolved as actioned')).toBeOnTheScreen();
    expect(mockResolve).not.toHaveBeenCalled();

    answer([], [hiddenItem]);
    await fireEvent.press(screen.getByTestId('confirm-ok'));

    await waitFor(() =>
      expect(mockResolve).toHaveBeenCalledWith('report-1', { action: 'HIDE_POST' }),
    );
    expect(await screen.findByTestId('moderation-empty')).toBeOnTheScreen();
    expect(screen.getByText('Nothing to review')).toBeOnTheScreen();
  });

  it('cancelling a confirmation changes nothing', async () => {
    answer([commentItem], []);
    await renderPage();
    await screen.findByTestId('report-card-comment-1');

    await fireEvent.press(screen.getByTestId('report-dismiss'));
    expect(screen.getByText('• Its open report is dismissed')).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('confirm-cancel'));

    expect(mockResolve).not.toHaveBeenCalled();
    expect(screen.getByTestId('report-card-comment-1')).toBeOnTheScreen();
  });

  it('lists decisions on the Resolved tab and restores a hidden post after a confirmation', async () => {
    answer([], [hiddenItem]);
    await renderPage();
    expect(await screen.findByText('Nothing to review')).toBeOnTheScreen();

    await fireEvent.press(screen.getByTestId('moderation-tab-resolved'));

    const card = await screen.findByTestId('report-card-post-1');
    expect(within(card).getByTestId('report-resolution')).toHaveTextContent(
      /Hidden by @admin · 2026-10-04 13:00 · now HIDDEN/,
    );
    expect(within(card).queryByText('Hide post')).toBeNull();
    await fireEvent.press(within(card).getByTestId('report-restore'));
    expect(screen.getByText('Restore this post?')).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('confirm-ok'));

    await waitFor(() => expect(mockRestore).toHaveBeenCalledWith('post-1'));
  });

  it('keeps the dialog open with the reason when the author has posted again', async () => {
    answer([], [hiddenItem]);
    mockRestore.mockRejectedValueOnce(new ApiError(409, 'POST_REPLACED', 'replaced'));
    await renderPage();
    await fireEvent.press(screen.getByTestId('moderation-tab-resolved'));
    await fireEvent.press(await screen.findByTestId('report-restore'));
    await fireEvent.press(screen.getByTestId('confirm-ok'));

    expect(await screen.findByText(/The author has posted again for this gig/)).toBeOnTheScreen();
    expect(screen.getByText('Restore this post?')).toBeOnTheScreen();
  });

  it('says so when the reports cannot be loaded, and retries', async () => {
    mockAnswer = async () => {
      throw new Error('offline');
    };
    await renderPage();
    expect(await screen.findByTestId('moderation-error')).toBeOnTheScreen();

    answer([postItem], []);
    await fireEvent.press(screen.getByText('Try again'));
    expect(await screen.findByTestId('report-card-post-1')).toBeOnTheScreen();
  });
});
