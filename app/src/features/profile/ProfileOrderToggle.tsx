import { StyleSheet, View } from 'react-native';

import { Chip } from '@/components/ui/Chip';
import { spacing } from '@/theme/tokens';

import { useProfileOrder } from './profileOrder';

/** Above a profile's grid: list the posts by gig date or by when they were posted. */
export function ProfileOrderToggle() {
  const order = useProfileOrder((state) => state.order);
  const setOrder = useProfileOrder((state) => state.setOrder);
  return (
    <View role="radiogroup" aria-label="Order of posts" style={styles.row} testID="profile-order">
      <Chip label="Gig date" selected={order === 'gig'} onPress={() => setOrder('gig')} />
      <Chip
        label="Recently posted"
        selected={order === 'posted'}
        onPress={() => setOrder('posted')}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
});
