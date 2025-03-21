import { QueryClient } from '@tanstack/react-query';

/**
 * The app's single TanStack Query cache. It lives in a module (not in a component's state) so that code
 * outside React can reach it: signing out clears it, because cached responses are viewer-relative
 * (`myAttendance`, `isFollowing`, distances from the viewer's city) and must not be shown to whoever signs
 * in next on the same device.
 */
export const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 10_000, retry: 1 } },
});
