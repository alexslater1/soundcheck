import { Stack } from 'expo-router';

import { LocationScreen } from '@/features/settings/LocationScreen';

export default function LocationRoute() {
  return (
    <>
      <Stack.Screen options={{ title: 'Your city' }} />
      <LocationScreen />
    </>
  );
}
