import { useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';

import { CityForm } from '@/features/onboarding/CityForm';

import { refreshAfterTasteChange } from './refreshAfterTasteChange';

/**
 * Settings → Your city: the sign-up's city step, changeable. Gigs near you and the For You feed
 * follow at once.
 */
export function LocationScreen() {
  const router = useRouter();
  const client = useQueryClient();
  return (
    <CityForm
      saveLabel={(city) => (city ? `Save ${city.name}` : 'Pick a city')}
      onSaved={async () => {
        await refreshAfterTasteChange(client);
        router.back();
      }}
    />
  );
}
