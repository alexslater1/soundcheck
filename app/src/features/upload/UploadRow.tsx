import { Link } from 'expo-router';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { canPostAgain, compressionNote, megabytes, postNote, stateLabel } from './labels';
import type { UploadEntry } from './types';

type Props = {
  entry: UploadEntry;
  onRetry: () => void;
  /** Removes the upload, and its post if it has one; rejects when that could not be done. */
  onRemove: () => Promise<void>;
  /** Sends the post request again (it failed although the server has the clip). */
  onPostAgain: () => void;
  /** Opens the compose form for this upload; absent while the form is already open for it. */
  onWritePost?: () => void;
};

/** One upload in the list: what it is, where it is, and what can be done about it. */
export function UploadRow({ entry, onRetry, onRemove, onPostAgain, onWritePost }: Props) {
  const { colors } = useTheme();
  // Removing an upload that is already a post deletes the post too: ask first.
  const [confirming, setConfirming] = useState(false);
  const [removeProblem, setRemoveProblem] = useState<string | null>(null);
  const remove = () => {
    setConfirming(false);
    setRemoveProblem(null);
    onRemove().catch(() => setRemoveProblem('Could not delete the post. Try again.'));
  };
  const inFlight =
    entry.state === 'PREPARING' ||
    entry.state === 'QUEUED' ||
    entry.state === 'REQUESTING' ||
    entry.state === 'UPLOADING' ||
    entry.state === 'COMPLETING';
  const note = compressionNote(entry);
  const post = postNote(entry);
  // Nothing was written for this clip yet (the form was closed, or the page reloaded): offer it again.
  const canWrite = !entry.draft && !entry.postId && entry.state !== 'REJECTED';
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
      {post ? (
        <Text
          variant="caption"
          muted={!post.problem}
          style={post.problem ? { color: colors.danger } : undefined}
          testID={`upload-${entry.localId}-post`}
        >
          {post.text}
        </Text>
      ) : null}
      {entry.error ? (
        <Text variant="caption" style={{ color: colors.danger }}>
          {entry.error}
        </Text>
      ) : null}
      <View style={styles.actions}>
        {entry.postId ? (
          <Link href={{ pathname: '/post/[id]', params: { id: entry.postId } }} asChild>
            <Button title="View post" accessibilityRole="link" />
          </Link>
        ) : entry.mediaId && (entry.state === 'READY' || entry.state === 'PROCESSING') ? (
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
        {canPostAgain(entry) ? <Button title="Post again" onPress={onPostAgain} /> : null}
        {canWrite && onWritePost ? (
          <Button title="Write post" variant="secondary" onPress={onWritePost} />
        ) : null}
        {!inFlight && !confirming ? (
          <Button
            title="Remove"
            variant="secondary"
            onPress={() => (entry.postId ? setConfirming(true) : remove())}
          />
        ) : null}
      </View>
      {confirming ? (
        <View style={styles.confirm} testID={`upload-${entry.localId}-confirm`}>
          <Text variant="caption">This clip is a post. Removing it deletes the post too.</Text>
          <View style={styles.actions}>
            <Button title="Delete post" variant="danger" onPress={remove} />
            <Button title="Keep it" variant="secondary" onPress={() => setConfirming(false)} />
          </View>
        </View>
      ) : null}
      {removeProblem ? (
        <Text variant="caption" style={{ color: colors.danger }} accessibilityRole="alert">
          {removeProblem}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { borderWidth: 1, borderRadius: radius.md, padding: spacing.md, gap: spacing.xs },
  track: { height: 6, borderRadius: radius.pill, overflow: 'hidden' },
  bar: { height: 6 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm, marginTop: spacing.xs },
  confirm: { gap: spacing.xs, marginTop: spacing.xs },
});
