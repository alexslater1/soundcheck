import { useAuthStore } from '@/features/auth/store';

/** True when the signed-in account has the ADMIN role (`/me`'s `role`). Shapes the UI only. */
export function useIsAdmin(): boolean {
  return useAuthStore((s) => s.me?.role === 'ADMIN');
}
