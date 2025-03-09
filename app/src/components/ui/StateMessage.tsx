import { StyleSheet, View } from 'react-native';

import { spacing } from '@/theme/tokens';

import { Button } from './Button';
import { Text } from './Text';

/** The empty and error states every screen needs: a message and, for errors, a retry. */
export function StateMessage({
  title,
  detail,
  onRetry,
  testID,
}: {
  title: string;
  detail?: string;
  onRetry?: () => void;
  testID?: string;
}) {
  return (
    <View style={styles.box} testID={testID}>
      <Text variant="title">{title}</Text>
      {detail ? <Text muted>{detail}</Text> : null}
      {onRetry ? <Button title="Try again" variant="secondary" onPress={onRetry} /> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  box: { paddingVertical: spacing.xl, gap: spacing.sm, alignItems: 'flex-start' },
});
