import { Suspense, type ReactNode } from 'react';
import { View } from 'react-native';

import { Skeleton } from '@/components/ui/Skeleton';
import { spacing } from '@/theme/tokens';

/** What a console page shows while its code is fetched (each page is its own chunk on web). */
export function PageSuspense({ children }: { children: ReactNode }) {
  return (
    <Suspense
      fallback={
        <View style={{ gap: spacing.md }} testID="console-page-loading">
          <Skeleton height={38} width="30%" />
          <Skeleton height={240} />
        </View>
      }
    >
      {children}
    </Suspense>
  );
}
