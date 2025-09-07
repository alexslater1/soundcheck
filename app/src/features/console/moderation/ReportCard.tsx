import Ionicons from '@expo/vector-icons/Ionicons';
import { Image } from 'expo-image';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import type {
  CommentPreview,
  PostPreview,
  ReportItem,
  ResolveRequestAction,
} from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { VideoPlayer } from '@/features/player/VideoPlayer';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import {
  ACTION_DONE,
  ACTION_LABELS,
  actionsFor,
  canRestore,
  kindLabel,
  reasonLabels,
} from './moderationModel';

const when = (iso: string) => iso.slice(0, 16).replace('T', ' ');

/**
 * One reported post or comment and its reports: what was reported, in context, why and by how
 * many, and the decisions that fit it. On the Resolved tab, the decision and the target's status now,
 * with Restore for a post that is still hidden.
 */
export function ReportCard({
  item,
  onDecide,
  onRestore,
}: {
  item: ReportItem;
  onDecide: (item: ReportItem, action: ResolveRequestAction) => void;
  onRestore: (item: ReportItem) => void;
}) {
  const { colors } = useTheme();
  const resolution = item.resolution;
  return (
    <View
      style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}
      testID={`report-card-${item.id}`}
    >
      <View style={styles.head}>
        <View style={[styles.kind, { backgroundColor: colors.accentSoft }]}>
          <Text variant="label">{kindLabel(item.kind).toUpperCase()}</Text>
        </View>
        <Text style={styles.bold}>
          {item.reportCount} {item.reportCount === 1 ? 'report' : 'reports'}
        </Text>
        <Text variant="caption" muted>
          first {when(item.firstReportedAt)}
          {item.lastReportedAt !== item.firstReportedAt
            ? ` · last ${when(item.lastReportedAt)}`
            : ''}
        </Text>
      </View>
      <View style={styles.reasons}>
        {reasonLabels(item).map((label) => (
          <View key={label} style={[styles.reason, { borderColor: colors.danger }]}>
            <Text variant="caption" style={{ color: colors.danger }}>
              {label}
            </Text>
          </View>
        ))}
      </View>
      {item.latestDetails.map((text, i) => (
        <Text key={`${i}-${text}`} variant="caption" muted style={styles.quote}>
          “{text}”
        </Text>
      ))}

      {item.post ? <PostTarget post={item.post} /> : null}
      {item.comment ? <CommentTarget comment={item.comment} /> : null}
      {!item.post && !item.comment ? (
        <Text muted>This {kindLabel(item.kind).toLowerCase()} no longer exists.</Text>
      ) : null}

      {resolution ? (
        <View style={styles.footer}>
          <Text variant="caption" testID="report-resolution">
            {ACTION_DONE[resolution.action]}
            {resolution.resolvedBy ? ` by @${resolution.resolvedBy}` : ''} ·{' '}
            {when(resolution.resolvedAt)}
            {' · now '}
            {resolution.targetStatus}
          </Text>
          {canRestore(item) ? (
            <Button
              title="Restore"
              size="small"
              variant="outline"
              onPress={() => onRestore(item)}
              testID="report-restore"
            />
          ) : null}
        </View>
      ) : (
        <View style={styles.footer}>
          {actionsFor(item.kind).map((action) => (
            <Button
              key={action}
              title={ACTION_LABELS[action]}
              size="small"
              variant={action === 'DISMISS' ? 'secondary' : 'danger'}
              onPress={() => onDecide(item, action)}
              testID={`report-${action.toLowerCase()}`}
            />
          ))}
        </View>
      )}
    </View>
  );
}

/** The reported post: its cover with Play (the app's own web player), caption, author, gig and status. */
function PostTarget({ post }: { post: PostPreview }) {
  const { colors } = useTheme();
  const [playing, setPlaying] = useState(false);
  const playback = post.playback;
  const playable = !!playback?.hlsUrl && playback.kind !== 'IMAGE';
  return (
    <View style={styles.target}>
      <View style={styles.media}>
        {playing && playback?.hlsUrl ? (
          <VideoPlayer
            hlsUrl={playback.hlsUrl}
            clipKey={`moderation-${post.id}`}
            posterUrl={playback.posterUrl}
            blurhash={playback.blurhash}
            hasAudio={playback.hasAudio}
            active
            testID="report-player"
          />
        ) : (
          <View style={[styles.poster, { backgroundColor: colors.surfaceRaised }]}>
            {playback?.posterUrl ? (
              <Image
                source={{ uri: playback.posterUrl }}
                contentFit="cover"
                style={StyleSheet.absoluteFill}
                accessibilityLabel="Cover of the reported post"
              />
            ) : null}
            {playable ? (
              <Pressable
                accessibilityRole="button"
                accessibilityLabel="Play"
                onPress={() => setPlaying(true)}
                style={styles.play}
                testID="report-play"
              >
                <Ionicons name="play" size={28} color="#FFFFFF" />
              </Pressable>
            ) : null}
          </View>
        )}
      </View>
      <View style={styles.about}>
        <Text>{post.caption ?? 'No caption'}</Text>
        <Text variant="caption" muted>
          {post.author ? `@${post.author.handle}` : 'An account that is gone'}
          {post.clipCount > 1 ? ` · ${post.clipCount} clips` : ''}
        </Text>
        {post.gig ? (
          <Text variant="caption" muted>
            {post.gig.title} · {post.gig.startsAt.slice(0, 10)}
          </Text>
        ) : null}
        <Text variant="caption" testID="report-post-status">
          Status: {post.status}
        </Text>
      </View>
    </View>
  );
}

/** The reported comment and where it was written: under a post (its caption) or a gig (its title). */
function CommentTarget({ comment }: { comment: CommentPreview }) {
  const { colors } = useTheme();
  return (
    <View style={[styles.comment, { borderLeftColor: colors.border }]}>
      <Text variant="caption" muted>
        {comment.post
          ? `On a post: “${comment.post.caption ?? 'no caption'}”`
          : comment.gig
            ? `On the gig ${comment.gig.title} · ${comment.gig.startsAt.slice(0, 10)}`
            : 'Where it was written is gone'}
      </Text>
      <Text style={comment.deleted ? styles.deleted : undefined}>{comment.body}</Text>
      <Text variant="caption" muted>
        {comment.author ? `@${comment.author.handle}` : 'An account that is gone'} ·{' '}
        {when(comment.createdAt)}
        {comment.deleted ? ' · deleted' : ''}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  card: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.md, gap: spacing.sm },
  head: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, flexWrap: 'wrap' },
  kind: { borderRadius: radius.sm, paddingHorizontal: spacing.sm, paddingVertical: 2 },
  bold: { fontWeight: '700' },
  reasons: { flexDirection: 'row', gap: spacing.xs, flexWrap: 'wrap' },
  reason: {
    borderWidth: 1,
    borderRadius: radius.pill,
    paddingHorizontal: spacing.sm,
    paddingVertical: 2,
  },
  quote: { fontStyle: 'italic' },
  target: { flexDirection: 'row', gap: spacing.md, alignItems: 'flex-start' },
  media: { width: 160 },
  poster: {
    width: 160,
    aspectRatio: 9 / 16,
    borderRadius: radius.md,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
  },
  play: {
    width: 56,
    height: 56,
    borderRadius: 28,
    backgroundColor: 'rgba(0,0,0,0.55)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  about: { flex: 1, gap: spacing.xs },
  comment: { borderLeftWidth: 3, paddingLeft: spacing.sm, gap: spacing.xs },
  deleted: { textDecorationLine: 'line-through' },
  footer: {
    flexDirection: 'row',
    gap: spacing.sm,
    alignItems: 'center',
    justifyContent: 'flex-end',
    flexWrap: 'wrap',
  },
});
