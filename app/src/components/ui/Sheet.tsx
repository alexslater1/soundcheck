import Ionicons from '@expo/vector-icons/Ionicons';
import { useContext, useState, type ComponentProps, type ReactNode } from 'react';
import { Modal, Pressable, StyleSheet, View } from 'react-native';
import { SafeAreaInsetsContext } from 'react-native-safe-area-context';

import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

import { Text } from './Text';

type SheetProps = {
  visible: boolean;
  /** Tapping outside, the Android back button, or Escape on the web. */
  onClose: () => void;
  /** A short heading at the top of the sheet. */
  title?: string;
  children: ReactNode;
  testID?: string;
};

/**
 * A panel that rises from the bottom of the screen over a dimmed backdrop. It is a `Modal`, so it
 * draws over everything wherever it is rendered: a post's ⋯ menu works the same over a clip in the feed
 * and under one on the post page.
 */
export function Sheet({ visible, onClose, title, children, testID }: SheetProps) {
  const { colors } = useTheme();
  // The context, not the hook: the hook throws where no provider is mounted (unit tests).
  const insets = useContext(SafeAreaInsetsContext);
  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      statusBarTranslucent
      navigationBarTranslucent
      onRequestClose={onClose}
    >
      <View style={styles.root}>
        <Pressable
          style={styles.backdrop}
          onPress={onClose}
          accessibilityRole="button"
          // Not "Close": a sheet's own Close button must stay the only one by that name.
          accessibilityLabel="Dismiss"
          testID="sheet-backdrop"
        />
        <View
          testID={testID}
          accessibilityViewIsModal
          style={[
            styles.panel,
            {
              backgroundColor: colors.surface,
              borderColor: colors.border,
              paddingBottom: spacing.sm + (insets?.bottom ?? 0),
            },
          ]}
        >
          <View style={[styles.handle, { backgroundColor: colors.border }]} />
          {title ? (
            <Text variant="label" muted style={styles.title} accessibilityRole="header">
              {title.toUpperCase()}
            </Text>
          ) : null}
          {children}
        </View>
      </View>
    </Modal>
  );
}

type IconName = ComponentProps<typeof Ionicons>['name'];

/** One row of an {@link ActionSheet}. */
export type SheetAction = {
  /** Stable and unique within the sheet (React key). */
  key: string;
  label: string;
  icon: IconName;
  onPress: () => void;
  /** Red: the action removes something. */
  destructive?: boolean;
  /**
   * Ask before acting: the sheet turns into this question, with the action (again in red when
   * destructive) and Cancel. The action runs only on that second tap.
   */
  confirm?: { title: string; message?: string; confirmLabel?: string };
};

type ActionSheetProps = Omit<SheetProps, 'children'> & { actions: SheetAction[] };

/**
 * The app's ⋯ menu: compact rows of icon and label, the destructive ones in red, each closing the
 * sheet before it acts. A feature adds a row by adding an entry to `actions`.
 */
export function ActionSheet({ visible, onClose, title, actions, testID }: ActionSheetProps) {
  const [confirming, setConfirming] = useState<SheetAction | null>(null);
  // Every way out goes through here, so a sheet opened again starts at its list, not at a question.
  const close = () => {
    setConfirming(null);
    onClose();
  };
  const run = (action: SheetAction) => {
    close();
    action.onPress();
  };

  if (confirming?.confirm) {
    const { title: question, message, confirmLabel } = confirming.confirm;
    return (
      <Sheet visible={visible} onClose={close} testID={testID}>
        <View style={styles.question} testID="sheet-confirm">
          <Text style={styles.bold}>{question}</Text>
          {message ? <Text muted>{message}</Text> : null}
        </View>
        <SheetRow
          action={{ ...confirming, label: confirmLabel ?? confirming.label }}
          onPress={() => run(confirming)}
        />
        <SheetRow
          action={{ key: 'cancel', label: 'Cancel', icon: 'close', onPress: close }}
          onPress={close}
        />
      </Sheet>
    );
  }

  return (
    <Sheet visible={visible} onClose={close} title={title} testID={testID}>
      {actions.map((action) => (
        <SheetRow
          key={action.key}
          action={action}
          onPress={() => (action.confirm ? setConfirming(action) : run(action))}
        />
      ))}
    </Sheet>
  );
}

function SheetRow({ action, onPress }: { action: SheetAction; onPress: () => void }) {
  const { colors } = useTheme();
  const ink = action.destructive ? colors.danger : colors.text;
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      style={({ pressed }) => [
        styles.row,
        { backgroundColor: pressed ? colors.surfaceRaised : 'transparent' },
      ]}
    >
      <Ionicons name={action.icon} size={20} color={ink} />
      <Text style={[styles.label, { color: ink }]}>{action.label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, justifyContent: 'flex-end' },
  backdrop: {
    position: 'absolute',
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
    backgroundColor: 'rgba(0,0,0,0.5)',
  },
  panel: {
    width: '100%',
    maxWidth: 560,
    alignSelf: 'center',
    borderTopLeftRadius: radius.lg,
    borderTopRightRadius: radius.lg,
    borderWidth: StyleSheet.hairlineWidth,
    borderBottomWidth: 0,
    paddingTop: spacing.sm,
    paddingHorizontal: spacing.sm,
  },
  handle: {
    width: 36,
    height: 4,
    borderRadius: radius.pill,
    alignSelf: 'center',
    marginBottom: spacing.sm,
  },
  title: { paddingHorizontal: spacing.md, paddingBottom: spacing.xs },
  question: { gap: spacing.xs, paddingHorizontal: spacing.md, paddingVertical: spacing.sm },
  bold: { fontWeight: '700' },
  row: {
    minHeight: touchTarget + 4,
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    paddingHorizontal: spacing.md,
    borderRadius: radius.md,
  },
  label: { fontWeight: '600' },
});
