import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, within } from '@testing-library/react-native';

import type { AdminPipelineParams, PipelineAction, PipelineOverview } from '@/api/generated/model';
import { ApiError } from '@/api/http';
import { fireEvent } from '@/test/fireEvent';
import { ThemeProvider } from '@/theme/ThemeProvider';

import PipelinePage, { ATTEMPT_HINT, actionError } from './PipelinePage';
import { POLL_MS } from './pipelineModel';
import { asset, overview, STRANDED_ID } from './pipelineTestData';

let mockAnswer: () => Promise<PipelineOverview>;
const mockCalls: AdminPipelineParams[] = [];
const mockRetry = jest.fn<Promise<PipelineAction>, [string]>();
const mockFail = jest.fn<Promise<PipelineAction>, [string]>();

jest.mock('@/api/generated/admin-pipeline/admin-pipeline', () => ({
  getAdminPipelineQueryKey: (params?: AdminPipelineParams) => [
    '/api/v1/admin/pipeline',
    ...(params ? [params] : []),
  ],
  adminPipeline: (params: AdminPipelineParams) => {
    mockCalls.push(params);
    return mockAnswer();
  },
  adminPipelineRetry: (id: string) => mockRetry(id),
  adminPipelineFail: (id: string) => mockFail(id),
}));
jest.mock('expo-router', () => ({
  useLocalSearchParams: () => ({}),
  useRouter: () => ({ setParams: jest.fn() }),
}));

async function renderPage() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
      mutations: { gcTime: Infinity },
    },
  });
  await render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <PipelinePage />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('PipelinePage', () => {
  beforeEach(() => {
    mockCalls.length = 0;
    mockRetry.mockReset();
    mockFail.mockReset();
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('shows skeleton tiles while loading', async () => {
    mockAnswer = () => new Promise(() => {});
    await renderPage();
    expect(screen.getAllByLabelText('Loading').length).toBeGreaterThan(0);
    expect(screen.getByTestId('pipeline-updated')).toHaveTextContent('');
  });

  it('fills the tiles, counts the time since the last answer and asks again every 5 s', async () => {
    mockAnswer = async () => overview();
    await renderPage();
    expect(await screen.findByTestId('bar-job-1')).toBeOnTheScreen();
    expect(within(screen.getByTestId('tile-queued')).getByText('4')).toBeOnTheScreen();
    expect(within(screen.getByTestId('tile-dlq')).getByText('2')).toBeOnTheScreen();
    expect(within(screen.getByTestId('tile-processing')).getByText('2')).toBeOnTheScreen();
    expect(within(screen.getByTestId('tile-stranded')).getByText('1')).toBeOnTheScreen();
    expect(screen.getByTestId('pipeline-updated')).toHaveTextContent('updated just now');
    expect(mockCalls).toHaveLength(1);
    expect(mockCalls[0]?.from).toMatch(/Z$/);

    await act(async () => {
      jest.advanceTimersByTime(3000);
    });
    expect(screen.getByTestId('pipeline-updated')).toHaveTextContent('updated 3 s ago');
    await act(async () => {
      jest.advanceTimersByTime(POLL_MS - 3000);
    });
    expect(mockCalls).toHaveLength(2);
  });

  it('says when the queue is unreachable and blanks only its tiles', async () => {
    mockAnswer = async () =>
      overview({ queue: null, dlq: null, queueError: 'unreachable' } as never);
    await renderPage();
    expect(await screen.findByTestId('pipeline-queue-error')).toBeOnTheScreen();
    expect(within(screen.getByTestId('tile-queued')).getByText('—')).toBeOnTheScreen();
    expect(within(screen.getByTestId('tile-dlq')).getByText('—')).toBeOnTheScreen();
    expect(within(screen.getByTestId('tile-failed')).getByText('1')).toBeOnTheScreen();
  });

  it('shows the empty states when nothing needs attention and nothing ran', async () => {
    mockAnswer = async () =>
      overview({ attention: [], recentJobs: [], failuresByReason: [], dailyTranscode: [] });
    await renderPage();
    expect(await screen.findByText('Nothing needs attention')).toBeOnTheScreen();
    expect(screen.getByText('No transcode attempts yet')).toBeOnTheScreen();
    expect(screen.getByTestId('pipeline-failures-empty')).toBeOnTheScreen();
    expect(screen.getByTestId('pipeline-daily-empty')).toBeOnTheScreen();
  });

  it('shows an error state with a retry when the server does not answer', async () => {
    mockAnswer = async () => {
      throw new Error('down');
    };
    await renderPage();
    expect(await screen.findByTestId('pipeline-error')).toBeOnTheScreen();
  });

  it('draws each attempt as a stacked bar of its stage times', async () => {
    mockAnswer = async () => overview();
    await renderPage();
    await screen.findByTestId('bar-job-1');
    expect(screen.getByTestId('bar-job-1-transcode')).toHaveStyle({ width: '70%' });
    expect(screen.getByTestId('bar-job-1-download')).toHaveStyle({ width: '10%' });
    // The failed attempt never reached transcode or upload.
    expect(screen.queryByTestId('bar-job-2-transcode')).toBeNull();
    expect(screen.getByTestId('bar-job-2-download')).toHaveStyle({ width: '10%' });
    await fireEvent.press(screen.getByTestId('attempt-hint-toggle'));
    expect(screen.getByTestId('attempt-hint')).toHaveTextContent(ATTEMPT_HINT);
  });

  it('retries a failed clip after the confirmation says what will change', async () => {
    mockAnswer = async () => overview();
    mockRetry.mockResolvedValue({ mediaId: 'x', previousStatus: 'FAILED', status: 'UPLOADED' });
    await renderPage();
    const failed = asset();
    // A failed clip offers Retry only; Fail is for stranded clips.
    expect(screen.queryByTestId(`fail-${failed.id}`)).toBeNull();
    await fireEvent.press(await screen.findByTestId(`retry-${failed.id}`));
    expect(
      screen.getByText(
        '• Clip 87654321 goes from FAILED back to UPLOADED, with a fresh set of 3 attempts',
      ),
    ).toBeOnTheScreen();
    expect(mockRetry).not.toHaveBeenCalled();
    await fireEvent.press(screen.getByTestId('confirm-ok'));
    expect(mockRetry).toHaveBeenCalledWith(failed.id);
  });

  it('fails a stranded clip, and shows the refusal when it is no longer stranded', async () => {
    mockAnswer = async () => overview();
    mockFail.mockRejectedValue(
      new ApiError(
        409,
        'MEDIA_NOT_STRANDED',
        'Only a stranded clip can be failed; this one is READY',
      ),
    );
    await renderPage();
    const stranded = { id: STRANDED_ID };
    await fireEvent.press(await screen.findByTestId(`fail-${stranded.id}`));
    expect(
      screen.getByText('• Its abandoned attempt (no heartbeat for over 15 min) is closed'),
    ).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('confirm-ok'));
    expect(mockFail).toHaveBeenCalledWith(stranded.id);
    expect(
      await screen.findByText(
        'Not any more: Only a stranded clip can be failed; this one is READY. The list refreshes in a moment.',
      ),
    ).toBeOnTheScreen();
  });

  it('words a rejected file plainly', () => {
    expect(actionError(new ApiError(409, 'MEDIA_REJECTED', 'x'))).toBe(
      'The file itself is bad: retrying would not help.',
    );
    expect(actionError(new Error('?'))).toBe('Something went wrong. Try again.');
  });
});
