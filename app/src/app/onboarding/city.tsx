import { useRouter } from 'expo-router';

import { CityForm } from '@/features/onboarding/CityForm';

/** Sign-up step: where the user goes to gigs. */
export default function CityStep() {
  const router = useRouter();
  return (
    <CityForm
      title="Where do you go to gigs?"
      saveLabel={(city) => (city ? `Continue as ${city.name}` : 'Pick a city')}
      onSaved={() => router.push('/onboarding/genres')}
    />
  );
}
