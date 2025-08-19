import { View } from 'react-native';

import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';

import { navItem } from './nav';

/** A page that isn't built yet, so the nav is honest about it. */
export default function PlaceholderPage({ page }: { page: string }) {
  const item = navItem(page);
  return (
    <View testID={`console-placeholder-${page}`}>
      <Text variant="display">{item.label}</Text>
      <StateMessage title="Not built yet" detail={`${item.blurb}.`} />
    </View>
  );
}
