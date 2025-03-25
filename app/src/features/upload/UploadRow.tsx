import { Link } from 'expo-router';
import { StyleSheet, View } from 'react-native';

import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { compressionNote, megabytes, stateLabel } from './labels';
import type { UploadEntry } from './types';

type Props = {
  entry: UploadEntry;
  onRetry: () => void;
  onRemove: () => void;
};

/** One upload in the list: what it is, where it is, and what can be done about it. */
export function UploadRow({ entry, onRetry, onRemove }: Props) {
  const { colors } = useTheme();
  const inFlight =
    entry.state === 'QUEUED' ||
    entry.state === 'REQUESTING' ||
    entry.state === 'UPLOADING' ||
    entry.state === 'COMPLETING';
  const note = compressionNote(entry);
  const tone =
    entry.state === 'READY'
      ? colors.success
      : entry.state === 'FAILED' || entry.state === 'REJECTED'
        ? colors.danger
        : colors.textMuted;

  return (
    <View
      style={[styles.row, { backgroundColor: colors.surface, borderColor: colors.border }]}
      testID={`upload-${entry.localId}`}
    >
      <Text numberOfLines={1}>{entry.fileName ?? 'Recorded clip'}</Text>
      <Text variant="caption" muted>
        {megabytes(entry.bytes)}
        {entry.strategy === 'MULTIPART' ? ' · uploaded in parts' : ''}
      </Text>
      {note ? (
        <Text variant="caption" muted>
          {note}
        </Text>
      ) : null}
      <Text style={{ color: tone }} testID={`upload-${entry.localId}-state`}>
        {stateLabel(entry)}
      </Text>
      {inFlight ? (
        <View
          style={[styles.track, { backgroundColor: colors.surfaceRaised }]}
          accessible
          accessibilityRole="progressbar"
          accessibilityValue={{ min: 0, max: 100, now: Math.round(entry.progress * 100) }}
        >
          <View
            style={[
              styles.bar,
              { backgroundColor: colors.accent, width: `${Math.round(entry.progress * 100)}%` },
            ]}
          />
        </View>
      ) : null}
      {entry.error ? (
        <Text variant="caption" style={{ color: colors.danger }}>
          {entry.error}
        </Text>
      ) : null}
      <View style={styles.actions}>
        {entry.mediaId && (entry.state === 'READY' || entry.state === 'PROCESSING') ? (
          <Link href={{ pathname: '/clip/[id]', params: { id: entry.mediaId } }} asChild>
            <Button
              title={entry.state === 'READY' ? 'Watch' : 'View status'}
              variant={entry.state === 'READY' ? 'primary' : 'secondary'}
              accessibilityRole="link"
            />
          </Link>
        ) : null}
        {entry.state === 'FAILED' ? (
          <Button title="Retry" variant="secondary" onPress={onRetry} />
        ) : null}
        {!inFlight ? <Button title="Remove" variant="secondary" onPress={onRemove} /> : null}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  row: { borderWidth: 1, borderRadius: radius.md, padding: spacing.md, gap: spacing.xs },
  track: { height: 6, borderRadius: radius.pill, overflow: 'hidden' },
  bar: { height: 6 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm, marginTop: spacing.xs },
});
