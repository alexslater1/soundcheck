import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react-native';
import type { ReactNode } from 'react';
import { Text } from 'react-native';

import type {
  AdminListIngestionRunsParams,
  IngestionIssue,
  IngestionRun,
  IngestionRunDetail,
  IngestionSource,
  PageIngestionRun,
} from '@/api/generated/model';
import { ApiError } from '@/api/http';
import { fireEvent } from '@/test/fireEvent';
import { ThemeProvider } from '@/theme/ThemeProvider';

import IngestionPage from './IngestionPage';
import { RUN_POLL_MS, WAITING_NOTE_AFTER_MS } from './issueKinds';
import { useIngestionAlert } from './useIngestionAlert';

let mockSources: () => Promise<IngestionSource[]>;
let mockRuns: (params: AdminListIngestionRunsParams) => Promise<PageIngestionRun>;
let mockRun: (id: string) => Promise<IngestionRunDetail>;
let mockQueue: (source: string) => Promise<{ runId: string }>;
let mockStop: (id: string) => Promise<IngestionRun>;
const mockStopped: string[] = [];
const mockQueued: string[] = [];

jest.mock('@/api/generated/admin-ingestion/admin-ingestion', () => ({
  getAdminListIngestionSourcesQueryKey: () => ['/api/v1/admin/ingestion/sources'],
  getAdminListIngestionRunsQueryKey: (params?: object) => [
    '/api/v1/admin/ingestion/runs',
    ...(params ? [params] : []),
  ],
  getAdminGetIngestionRunQueryKey: (id: string) => [`/api/v1/admin/ingestion/runs/${id}`],
  adminListIngestionSources: () => mockSources(),
  adminListIngestionRuns: (params: AdminListIngestionRunsParams) => mockRuns(params),
  adminGetIngestionRun: (id: string) => mockRun(id),
  adminStopIngestionRun: (id: string) => {
    mockStopped.push(id);
    return mockStop(id);
  },
  adminQueueIngestionRun: ({ source }: { source: string }) => {
    mockQueued.push(source);
    return mockQueue(source);
  },
}));

jest.mock('expo-router', () => ({
  Link: ({ children }: { children: ReactNode }) => children,
}));

const run = (over: Partial<IngestionRun> = {}): IngestionRun => ({
  id: 'run-1',
  source: 'TICKETMASTER',
  trigger: 'SCHEDULE',
  status: 'SUCCEEDED',
  startedAt: '2026-10-05T05:10:00Z',
  finishedAt: '2026-10-05T05:11:30Z',
  fetched: 240,
  created: 3,
  updated: 4,
  unchanged: 226,
  merged: 0,
  skipped: 7,
  errors: 0,
  apiCalls: 0,
  issueCounts: {},
  ...over,
});

const source = (name: string, over: Partial<IngestionSource> = {}): IngestionSource => ({
  name,
  enabled: true,
  mode: 'fixtures',
  lastRun: run({ source: name }),
  ...over,
});

const detail = (
  r: IngestionRun,
  issues: IngestionIssue[] = [],
  stats: IngestionRunDetail['stats'] = {},
): IngestionRunDetail => ({
  run: r,
  stats,
  issues,
});

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
        <IngestionPage />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('IngestionPage', () => {
  beforeEach(() => {
    mockQueued.length = 0;
    mockStopped.length = 0;
    mockStop = async (id) => run({ id, status: 'STOPPED' });
    mockSources = async () => [
      source('TICKETMASTER'),
      source('VENUE', { enabled: false }),
      source('SETLISTFM', { enabled: false, lastRun: undefined }),
    ];
    mockRuns = async () => ({ items: [run()], nextCursor: null });
    mockRun = async (id) => detail(run({ id }));
    mockQueue = async () => ({ runId: 'queued-1' });
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('lists the runs and expands one into its issues, grouped by kind, unknown kinds raw', async () => {
    const withIssues = run({
      id: 'run-9',
      issueCounts: { NO_ARTISTS: 2, NO_DATE: 1, BRAND_NEW_KIND: 1 },
    });
    mockRuns = async () => ({ items: [withIssues], nextCursor: null });
    mockRun = async () =>
      detail(withIssues, [
        { kind: 'NO_DATE', externalId: 'e3', detail: 'no date' },
        { kind: 'NO_ARTISTS', externalId: 'e1', detail: 'Club night' },
        { kind: 'BRAND_NEW_KIND', externalId: null, detail: 'something new' },
        { kind: 'NO_ARTISTS', externalId: 'e2', detail: 'Quiz' },
      ]);
    await renderPage();

    expect(await screen.findByText(/NO_ARTISTS ×2/)).toBeOnTheScreen();
    expect(screen.queryByTestId('ingestion-issues-run-9')).toBeNull();
    await fireEvent.press(screen.getByTestId('ingestion-issues-toggle-run-9'));
    expect(await screen.findByTestId('ingestion-issues-run-9')).toBeOnTheScreen();

    expect(screen.getByText('NO_ARTISTS · 2 stored')).toBeOnTheScreen();
    expect(screen.getByTestId('issue-explanation-NO_ARTISTS')).toHaveTextContent(/line-up/);
    expect(screen.getByText('e1  Club night')).toBeOnTheScreen();
    // A kind the page doesn't know is shown as the backend named it.
    expect(screen.getByText('BRAND_NEW_KIND · 1 stored')).toBeOnTheScreen();
    expect(screen.getByTestId('issue-explanation-BRAND_NEW_KIND')).toHaveTextContent(
      /No explanation for this kind yet/,
    );
    // Biggest group first.
    const groups = screen.getAllByTestId(/^issue-group-/).map((g) => g.props.testID as string);
    expect(groups[0]).toBe('issue-group-NO_ARTISTS');

    await fireEvent.press(screen.getByTestId('ingestion-issues-toggle-run-9'));
    expect(screen.queryByTestId('ingestion-issues-run-9')).toBeNull();
  });

  it('says where to act on an issue that has a place, and on the near misses', async () => {
    const withIssues = run({ id: 'run-7', issueCounts: { MISSING_COORDINATES: 1, NO_ARTISTS: 1 } });
    mockRuns = async () => ({ items: [withIssues], nextCursor: null });
    mockRun = async () =>
      detail(
        withIssues,
        [
          { kind: 'MISSING_COORDINATES', externalId: 'e1', detail: 'New Venue' },
          { kind: 'NO_ARTISTS', externalId: 'e2', detail: 'Quiz night' },
        ],
        { flaggedForReview: 2 },
      );
    await renderPage();
    await fireEvent.press(await screen.findByTestId('ingestion-issues-toggle-run-7'));
    expect(await screen.findByTestId('issue-near-misses')).toHaveTextContent(
      '2 near misses to decide: Catalog → Matches',
    );
    expect(screen.getByTestId('issue-action-MISSING_COORDINATES')).toBeOnTheScreen();
    expect(screen.queryByTestId('issue-action-NO_ARTISTS')).toBeNull();
  });

  it('offers Run now only for an enabled source without a queued or running run', async () => {
    mockSources = async () => [
      source('TICKETMASTER'),
      source('VENUE', { enabled: false }),
      source('SETLISTFM', {
        mode: 'live',
        lastRun: run({ source: 'SETLISTFM', status: 'RUNNING' }),
      }),
    ];
    await renderPage();

    const ticketmaster = await screen.findByTestId('ingestion-run-now-TICKETMASTER');
    expect(ticketmaster).toBeEnabled();
    expect(screen.getByTestId('ingestion-run-now-VENUE')).toBeDisabled();
    expect(screen.getByText('Not enabled in this deployment')).toBeOnTheScreen();
    expect(screen.getByTestId('ingestion-run-now-SETLISTFM')).toBeDisabled();
    expect(screen.getByText('In progress')).toBeOnTheScreen();
  });

  it('queues a run, polls it every 3 s until it ends, then enables the button again', async () => {
    jest.useFakeTimers();
    let status: IngestionRun['status'] = 'QUEUED';
    const runIds: string[] = [];
    mockRun = async (id) => {
      runIds.push(id);
      return detail(
        run({ id, status, finishedAt: status === 'SUCCEEDED' ? '2026-10-05T05:12:00Z' : null }),
      );
    };
    await renderPage();
    await fireEvent.press(await screen.findByTestId('ingestion-run-now-TICKETMASTER'));

    expect(mockQueued).toEqual(['TICKETMASTER']);
    expect(await screen.findByText('Queued…')).toBeOnTheScreen();
    expect(screen.getByTestId('ingestion-run-now-TICKETMASTER')).toBeDisabled();

    status = 'RUNNING';
    await act(async () => {
      jest.advanceTimersByTime(RUN_POLL_MS);
    });
    expect(await screen.findByText('Running…')).toBeOnTheScreen();

    status = 'SUCCEEDED';
    await act(async () => {
      jest.advanceTimersByTime(RUN_POLL_MS);
    });
    expect(
      await screen.findByText('Finished SUCCEEDED: 240 fetched, 3 created, 4 updated'),
    ).toBeOnTheScreen();
    expect(screen.getByTestId('ingestion-run-now-TICKETMASTER')).toBeEnabled();
    expect(new Set(runIds)).toEqual(new Set(['queued-1']));
    const polls = runIds.length;
    await act(async () => {
      jest.advanceTimersByTime(RUN_POLL_MS * 3);
    });
    expect(runIds.length).toBe(polls); // no polling once it has ended
  });

  it('shows the queued run in the history at once, without a refresh', async () => {
    const queued = run({
      id: 'queued-1',
      trigger: 'CONSOLE',
      status: 'QUEUED',
      finishedAt: null,
      fetched: 0,
      created: 0,
      updated: 0,
      skipped: 0,
    });
    let pressed = false;
    mockSources = async () => [
      source('TICKETMASTER', pressed ? { lastRun: queued } : {}),
      source('VENUE', { enabled: false }),
      source('SETLISTFM', { enabled: false, lastRun: undefined }),
    ];
    mockRuns = async () => ({ items: pressed ? [queued, run()] : [run()], nextCursor: null });
    mockRun = async (id) => detail({ ...queued, id });
    mockQueue = async () => {
      pressed = true;
      return { runId: 'queued-1' };
    };
    await renderPage();
    await fireEvent.press(await screen.findByTestId('ingestion-run-now-TICKETMASTER'));

    // Before the fix nothing re-read the history until the run ended (or the page was refreshed).
    const table = screen.getByTestId('ingestion-runs');
    expect(await within(table).findByText('QUEUED')).toBeOnTheScreen();
    // A run still going has no counts yet: they are written when it ends.
    expect(within(table).getAllByText('—')).toHaveLength(2);
  });

  it('stops a running run after confirming, then shows it stopping', async () => {
    const running = run({
      id: 'run-live',
      status: 'RUNNING',
      finishedAt: null,
      trigger: 'SCHEDULE',
    });
    let stopAsked = false;
    mockSources = async () => [
      source('TICKETMASTER', {
        lastRun: stopAsked ? { ...running, stopRequestedAt: '2026-10-05T17:00:00Z' } : running,
      }),
      source('VENUE', { enabled: false }),
      source('SETLISTFM', { enabled: false, lastRun: undefined }),
    ];
    mockStop = async (id) => {
      stopAsked = true;
      return { ...running, id, stopRequestedAt: '2026-10-05T17:00:00Z' };
    };
    await renderPage();

    await fireEvent.press(await screen.findByTestId('ingestion-stop-TICKETMASTER'));
    expect(mockStopped).toEqual([]);
    expect(
      screen.getByText('• Ask the run to stop at its next heartbeat, within about 30 seconds'),
    ).toBeOnTheScreen();
    expect(screen.getByText('• Keep the gigs it has already stored')).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('confirm-ok'));

    expect(mockStopped).toEqual(['run-live']);
    await waitFor(() =>
      expect(screen.getByTestId('ingestion-stop-TICKETMASTER')).toHaveTextContent('Stopping…'),
    );
    expect(screen.getByTestId('ingestion-stop-TICKETMASTER')).toBeDisabled();
  });

  it('offers Stop for a queued run, which never starts', async () => {
    mockRun = async (id) => detail(run({ id, status: 'QUEUED', finishedAt: null }));
    await renderPage();
    expect(screen.queryByTestId('ingestion-stop-TICKETMASTER')).toBeNull();
    await fireEvent.press(await screen.findByTestId('ingestion-run-now-TICKETMASTER'));
    await fireEvent.press(await screen.findByTestId('ingestion-stop-TICKETMASTER'));
    expect(
      screen.getByText('• Close the queued run as STOPPED; it never starts'),
    ).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('confirm-ok'));
    expect(mockStopped).toEqual(['queued-1']);
  });

  it('says it is waiting for the scheduler after 60 s still queued', async () => {
    jest.useFakeTimers();
    mockRun = async (id) => detail(run({ id, status: 'QUEUED', finishedAt: null }));
    await renderPage();
    await fireEvent.press(await screen.findByTestId('ingestion-run-now-TICKETMASTER'));
    expect(await screen.findByText('Queued…')).toBeOnTheScreen();

    await act(async () => {
      jest.advanceTimersByTime(WAITING_NOTE_AFTER_MS - 2_000);
    });
    expect(screen.queryByTestId('ingestion-waiting-TICKETMASTER')).toBeNull();
    await act(async () => {
      jest.advanceTimersByTime(3_000);
    });
    expect(screen.getByTestId('ingestion-waiting-TICKETMASTER')).toHaveTextContent(
      /Waiting for the scheduler process/,
    );
  });

  it('explains a refused Run now', async () => {
    mockQueue = async () => {
      throw new ApiError(409, 'INGESTION_ALREADY_RUNNING', 'conflict');
    };
    await renderPage();
    await fireEvent.press(await screen.findByTestId('ingestion-run-now-TICKETMASTER'));
    expect(
      await screen.findByText('This source already has a queued or running run.'),
    ).toBeOnTheScreen();
  });

  it('highlights a VENUE run whose site was empty or unreadable, and no other run', async () => {
    mockRuns = async () => ({
      items: [
        run({ id: 'v1', source: 'VENUE', issueCounts: { VENUE_EMPTY: 1 } }),
        run({ id: 'v2', source: 'VENUE', issueCounts: { NO_DATE: 2 } }),
        run({ id: 't1', source: 'TICKETMASTER', issueCounts: { VENUE_UNREADABLE: 1 } }),
      ],
      nextCursor: null,
    });
    await renderPage();
    expect(await screen.findAllByTestId('run-breakage')).toHaveLength(1);
    expect(screen.getByText('A venue site may have changed')).toBeOnTheScreen();
  });

  it('shows the empty and error states', async () => {
    mockRuns = async () => ({ items: [], nextCursor: null });
    mockSources = async () => {
      throw new Error('500');
    };
    await renderPage();
    expect(await screen.findByText('No ingestion runs yet')).toBeOnTheScreen();
    expect(screen.getByText("Couldn't load the gig sources")).toBeOnTheScreen();
  });

  it('filters the runs by source and status', async () => {
    const calls: AdminListIngestionRunsParams[] = [];
    mockRuns = async (p) => {
      calls.push(p);
      return { items: [], nextCursor: null };
    };
    await renderPage();
    await fireEvent.press(await screen.findByTestId('ingestion-filter-source-VENUE'));
    await fireEvent.press(screen.getByTestId('ingestion-filter-status-FAILED'));
    expect(calls.at(-1)).toEqual({ source: 'VENUE', status: 'FAILED' });
    expect(await screen.findByText('No runs match these filters')).toBeOnTheScreen();
  });
});

function AlertProbe() {
  return <Text>{useIngestionAlert() ? 'alert' : 'quiet'}</Text>;
}

describe('useIngestionAlert', () => {
  async function probe() {
    const client = new QueryClient({
      defaultOptions: {
        queries: { retry: false, gcTime: Infinity },
        mutations: { gcTime: Infinity },
      },
    });
    await render(
      <QueryClientProvider client={client}>
        <AlertProbe />
      </QueryClientProvider>,
    );
  }

  it('asks for the latest VENUE run only, and raises the alert when its site broke', async () => {
    const calls: AdminListIngestionRunsParams[] = [];
    mockRuns = async (p) => {
      calls.push(p);
      return {
        items: [run({ source: 'VENUE', issueCounts: { VENUE_UNREADABLE: 1 } })],
        nextCursor: 'more',
      };
    };
    await probe();
    expect(await screen.findByText('alert')).toBeOnTheScreen();
    expect(calls).toEqual([{ source: 'VENUE', limit: 1 }]);
  });

  it('stays quiet for a healthy venue run, or none', async () => {
    let asked = 0;
    mockRuns = async () => {
      asked++;
      return { items: [run({ source: 'VENUE' })], nextCursor: null };
    };
    await probe();
    await waitFor(() => expect(asked).toBe(1));
    expect(await screen.findByText('quiet')).toBeOnTheScreen();
  });
});
