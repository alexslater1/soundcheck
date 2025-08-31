import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react-native';
import type { ReactNode } from 'react';

import type { RankingPreview, RankingPreviewRequest } from '@/api/generated/model';

import { PREVIEW_DEBOUNCE_MS, usePreview } from './usePreview';

type Pending = {
  request: RankingPreviewRequest;
  signal: AbortSignal | undefined;
  resolve: (p: RankingPreview) => void;
};
const mockPending: Pending[] = [];

jest.mock('@/api/generated/admin-ranking/admin-ranking', () => ({
  adminPreviewRanking: (request: RankingPreviewRequest, options?: { signal?: AbortSignal }) =>
    new Promise<RankingPreview>((resolve) => {
      mockPending.push({ request, signal: options?.signal, resolve });
    }),
}));

/** A preview whose only item records which request it answers (its recency weight). */
function answer(request: RankingPreviewRequest): RankingPreview {
  const recency = request.configB?.weights?.recency ?? -1;
  return {
    a: { configVersion: 1, items: [] },
    b: { configVersion: 0, items: [] },
    candidates: recency,
    commonItems: 0,
  };
}

function request(recency: number, userId = 'u1'): RankingPreviewRequest {
  return { userId, configA: { id: 'active' }, configB: { weights: { recency } }, limit: 20 };
}

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

/** Lets React Query deliver what has resolved (it notifies on a timer, which the fake clock holds). */
async function flush() {
  await act(async () => jest.advanceTimersByTime(1));
}

beforeEach(() => {
  jest.useFakeTimers();
  mockPending.length = 0;
});
afterEach(() => jest.useRealTimers());

describe('usePreview', () => {
  it('asks once for a burst of slider moves, with the last value', async () => {
    const { rerender } = await renderHook(({ r }: { r: RankingPreviewRequest }) => usePreview(r), {
      wrapper,
      initialProps: { r: request(1.5) },
    });
    await act(async () => jest.advanceTimersByTime(PREVIEW_DEBOUNCE_MS));
    expect(mockPending).toHaveLength(1);

    for (const recency of [1.4, 1.2, 0.9, 0.5]) {
      await rerender({ r: request(recency) });
      await act(async () => jest.advanceTimersByTime(100));
    }
    expect(mockPending).toHaveLength(1);
    await act(async () => jest.advanceTimersByTime(PREVIEW_DEBOUNCE_MS));
    expect(mockPending).toHaveLength(2);
    expect(mockPending[1]!.request.configB?.weights?.recency).toBe(0.5);
  });

  it('never shows a stale answer as the current one, and aborts the request it replaced', async () => {
    const { result, rerender } = await renderHook(
      ({ r }: { r: RankingPreviewRequest }) => usePreview(r),
      {
        wrapper,
        initialProps: { r: request(1.5) },
      },
    );
    await act(async () => jest.advanceTimersByTime(PREVIEW_DEBOUNCE_MS));
    await rerender({ r: request(0) });
    await act(async () => jest.advanceTimersByTime(PREVIEW_DEBOUNCE_MS));
    expect(mockPending).toHaveLength(2);
    expect(mockPending[0]!.signal?.aborted).toBe(true);

    // The newer request answers first; then the older one's answer arrives late.
    await act(async () => mockPending[1]!.resolve(answer(mockPending[1]!.request)));
    await act(async () => mockPending[0]!.resolve(answer(mockPending[0]!.request)));
    await flush();

    expect(result.current.data?.candidates).toBe(0);
    expect(result.current.updating).toBe(false);
  });

  it("keeps the last answer up while the next loads, but not another user's", async () => {
    const { result, rerender } = await renderHook(
      ({ r }: { r: RankingPreviewRequest }) => usePreview(r),
      {
        wrapper,
        initialProps: { r: request(1.5) },
      },
    );
    await act(async () => jest.advanceTimersByTime(PREVIEW_DEBOUNCE_MS));
    await act(async () => mockPending[0]!.resolve(answer(mockPending[0]!.request)));
    await flush();
    expect(result.current.data?.candidates).toBe(1.5);

    await rerender({ r: request(1) });
    expect(result.current.updating).toBe(true);
    await act(async () => jest.advanceTimersByTime(PREVIEW_DEBOUNCE_MS));
    expect(result.current.data?.candidates).toBe(1.5); // still the last answer, marked updating
    expect(result.current.updating).toBe(true);

    await rerender({ r: request(1, 'u2') });
    await act(async () => jest.advanceTimersByTime(PREVIEW_DEBOUNCE_MS));
    await flush();
    expect(result.current.data).toBeUndefined();
  });
});
