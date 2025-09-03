import { useRouter } from 'expo-router';
import { useEffect } from 'react';

import { useAuthStore } from '@/features/auth/store';

import { pendingRecording, type PickResult } from './pickVideo';

/** A recording collected at start-up, waiting for the Create tab to take it. */
let waiting: PickResult | null = null;

/** The Create tab takes the recovered recording once; null when there is none. */
export function takeRecovered(): PickResult | null {
  const recovered = waiting;
  waiting = null;
  return recovered;
}

/**
 * Mounted at the root: once someone is signed in after a start, asks whether the camera left a recording
 * for an app Android had killed (`pendingRecording`). If it did, the clip is kept and Create opens with
 * it under "Your clips"; the gig is chosen again there (gig and clips go in either order).
 */
export function useRecoveredRecording(): void {
  const userId = useAuthStore((s) => (s.status === 'signedIn' && s.me?.onboarded ? s.me.id : null));
  const router = useRouter();
  useEffect(() => {
    if (!userId) return;
    let live = true;
    void pendingRecording()
      .then((recovered) => {
        if (live && recovered) {
          waiting = recovered;
          router.push('/create');
        }
      })
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [userId, router]);
}
