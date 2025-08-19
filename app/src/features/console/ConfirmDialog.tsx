import { Modal, StyleSheet, View } from 'react-native';

import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

/**
 * The one confirmation every destructive console action goes through (activate, hide, merge, delete,
 * fail). It says exactly what will change, one line per change, before anything is sent.
 */
export function ConfirmDialog({
  visible,
  title,
  changes,
  confirmLabel,
  destructive = false,
  busy = false,
  error,
  onConfirm,
  onCancel,
}: {
  visible: boolean;
  title: string;
  /** What will change, one line each ("Config v4 becomes active", "v3 is archived"). */
  changes: readonly string[];
  confirmLabel: string;
  destructive?: boolean;
  busy?: boolean;
  /** Shown when the action failed; the dialog stays open so it can be retried. */
  error?: string | null;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const { colors } = useTheme();
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onCancel}>
      <View style={styles.backdrop}>
        <View
          accessibilityRole="alert"
          accessibilityLabel={title}
          style={[styles.dialog, { backgroundColor: colors.surface, borderColor: colors.border }]}
        >
          <Text variant="title">{title}</Text>
          <Text muted>This will:</Text>
          <View style={styles.changes}>
            {changes.map((c) => (
              <Text key={c}>• {c}</Text>
            ))}
          </View>
          {error ? <Text style={{ color: colors.danger }}>{error}</Text> : null}
          <View style={styles.buttons}>
            <Button
              title="Cancel"
              variant="secondary"
              onPress={onCancel}
              disabled={busy}
              testID="confirm-cancel"
            />
            <Button
              title={busy ? 'Working…' : confirmLabel}
              variant={destructive ? 'danger' : 'primary'}
              onPress={onConfirm}
              disabled={busy}
              testID="confirm-ok"
            />
          </View>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0,0,0,0.55)',
    padding: spacing.lg,
  },
  dialog: {
    width: '100%',
    maxWidth: 480,
    borderWidth: 1,
    borderRadius: radius.lg,
    padding: spacing.lg,
    gap: spacing.sm,
  },
  changes: { gap: spacing.xs, marginBottom: spacing.sm },
  buttons: { flexDirection: 'row', justifyContent: 'flex-end', gap: spacing.sm },
});
