import { LazyIngestionPage } from '@/features/console/lazy';
import { PageSuspense } from '@/features/console/PageSuspense';

export default function ConsoleIngestion() {
  return (
    <PageSuspense>
      <LazyIngestionPage />
    </PageSuspense>
  );
}
