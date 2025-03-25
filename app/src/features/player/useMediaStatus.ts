import { useGetMedia } from '@/api/generated/media/media';

import { nextPollDelay } from './mediaPolling';

/**
 * A clip's playback info, re-fetched with a backoff until the worker has finished with it.
 * Stops by itself at READY / REJECTED / FAILED or after five minutes.
 */
export function useMediaStatus(mediaId: string) {
  return useGetMedia(mediaId, {
    query: {
      refetchInterval: (query) =>
        nextPollDelay(query.state.data?.status, query.state.dataUpdateCount),
    },
  });
}
