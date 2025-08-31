import { LazyInspectorPage } from '@/features/console/lazy';
import { PageSuspense } from '@/features/console/PageSuspense';

export default function ConsoleInspector() {
  return (
    <PageSuspense>
      <LazyInspectorPage />
    </PageSuspense>
  );
}
