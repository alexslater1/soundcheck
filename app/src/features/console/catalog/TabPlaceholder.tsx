import { StateMessage } from '@/components/ui/StateMessage';

/** A Catalog tab that not built yet: honest about it, like PlaceholderPage. */
export function TabPlaceholder({ blurb }: { blurb: string }) {
  return (
    <StateMessage
      testID="catalog-tab-placeholder"
      title="Not built yet"
      detail={`${blurb}. This tab is not built yet.`}
    />
  );
}
