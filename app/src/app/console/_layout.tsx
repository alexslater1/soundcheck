import { Slot } from 'expo-router';

import { ConsoleGate } from '@/features/console/ConsoleGate';

/**
 * The console: web only, admins only. The guard and the lazily loaded shell live in
 * `features/console`; the server checks the role on every admin request regardless (two locks).
 */
export default function ConsoleLayout() {
  return (
    <ConsoleGate>
      <Slot />
    </ConsoleGate>
  );
}
