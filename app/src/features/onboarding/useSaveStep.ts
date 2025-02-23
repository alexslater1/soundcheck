import { useCallback, useState } from 'react';

import { ApiError } from '@/api/http';

/**
 * Runs an onboarding step's save with a busy flag and a user-facing error (every screen has an
 * error state). A failed save leaves the user on the step with the message shown and the button enabled.
 */
export function useSaveStep() {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const run = useCallback(async (action: () => Promise<void>) => {
    setSaving(true);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(saveErrorMessage(e));
    } finally {
      setSaving(false);
    }
  }, []);

  return { saving, error, run };
}

/** API problems carry a readable `detail`; anything else gets a generic retry message. */
export function saveErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.code === 'NETWORK_ERROR'
      ? 'Could not reach the server. Check your connection and try again.'
      : error.message;
  }
  return 'Something went wrong. Please try again.';
}
