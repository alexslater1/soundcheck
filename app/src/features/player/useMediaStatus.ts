import { useGetMedia } from '@/api/generated/media/media';

import { clipPollInterval } from './mediaPolling';

/**
 * A clip's playback info, re-fetched with a backoff until the worker has finished with it. Stops by
 * itself at READY / REJECTED / FAILED, after five minutes, or when a request fails.
 */
export function useMediaStatus(mediaId: string) {
  return useGetMedia(mediaId, {
    query: {
      refetchInterval: (query) => clipPollInterval(query.state),
    },
  });
}
