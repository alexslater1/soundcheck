import { LazyPipelinePage } from '@/features/console/lazy';
import { PageSuspense } from '@/features/console/PageSuspense';

export default function ConsolePipeline() {
  return (
    <PageSuspense>
      <LazyPipelinePage />
    </PageSuspense>
  );
}
