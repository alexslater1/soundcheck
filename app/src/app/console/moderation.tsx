import { LazyModerationPage } from '@/features/console/lazy';
import { PageSuspense } from '@/features/console/PageSuspense';

export default function ConsoleModeration() {
  return (
    <PageSuspense>
      <LazyModerationPage />
    </PageSuspense>
  );
}
