import { LazyOverviewPage } from '@/features/console/lazy';
import { PageSuspense } from '@/features/console/PageSuspense';

// The console's landing page: KPIs, the synthetic share and the feed charts.
export default function ConsoleIndex() {
  return (
    <PageSuspense>
      <LazyOverviewPage />
    </PageSuspense>
  );
}
