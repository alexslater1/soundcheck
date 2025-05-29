import { ApiError } from '@/api/http';

/** True for the server's "this feed session is gone" answer (410 FEED_SESSION_EXPIRED). */
export function isSessionExpired(error: unknown): boolean {
  return error instanceof ApiError && error.status === 410;
}
