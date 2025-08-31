import { LazyRankingPage } from '@/features/console/lazy';
import { PageSuspense } from '@/features/console/PageSuspense';

export default function ConsoleRanking() {
  return (
    <PageSuspense>
      <LazyRankingPage />
    </PageSuspense>
  );
}
