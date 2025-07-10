import { useCallback, useState } from 'react';

import { useAuthStore } from '@/features/auth/store';

import { pickAvatar, setAvatar } from './avatar';

/**
 * Change the profile photo: the photo picker, then the upload; the profile is reloaded so every
 * screen shows the new photo. Used by the photo on Me and by Settings → Edit profile.
 */
export function useChangeAvatar() {
  const reloadMe = useAuthStore((s) => s.reloadMe);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const change = useCallback(async () => {
    setError(null);
    let image;
    try {
      image = await pickAvatar();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'That photo cannot be used.');
      return;
    }
    if (!image) return;
    setSaving(true);
    try {
      await setAvatar(image);
      await reloadMe();
    } catch {
      setError('Could not save the photo. Check your connection and try again.');
    } finally {
      setSaving(false);
    }
  }, [reloadMe]);

  return { change, saving, error };
}
