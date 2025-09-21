import { StyleSheet, View } from 'react-native';

import { spacing } from '@/theme/tokens';

import type { CatalogTabProps } from './tabTypes';
import { VenueLocations } from './VenueLocations';
import { VenueMerge } from './VenueMerge';

/**
 * The Venues tab: merge duplicate venues, then put suspect venues where they
 * really are. Two independent sections; a same-night pair a venue merge creates opens the Merge tab.
 */
export function VenuesTab({ openMerge }: CatalogTabProps) {
  return (
    <View style={styles.page} testID="catalog-venues">
      <VenueMerge openMerge={openMerge} />
      <VenueLocations />
    </View>
  );
}

const styles = StyleSheet.create({
  page: { gap: spacing.xl },
});
