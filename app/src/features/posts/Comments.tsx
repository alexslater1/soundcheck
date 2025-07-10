import Ionicons from '@expo/vector-icons/Ionicons';
import { useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, TextInput, View } from 'react-native';

import {
  addComment,
  deleteComment,
  getListCommentsQueryKey,
  useListComments,
} from '@/api/generated/engagement/engagement';
import type { CommentView } from '@/api/generated/model';
import { getGetPostQueryKey } from '@/api/generated/posts/posts';
import { Avatar } from '@/components/ui/Avatar';
import { Button } from '@/components/ui/Button';
import { ActionSheet } from '@/components/ui/Sheet';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { useTrack } from '@/features/telemetry/TelemetryScope';
import { newUuid } from '@/features/upload/uuid';
import { relativeTime, relativeTimeLabel, useNow } from '@/lib/relativeTime';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

/** Refreshes a post's comments and its counter after one was added or deleted. */
function useRefreshComments(postId: string) {
  const client = useQueryClient();
  return async () => {
    await client.invalidateQueries({ queryKey: getListCommentsQueryKey(postId) });
    await client.invalidateQueries({ queryKey: getGetPostQueryKey(postId) });
  };
}

/**
 * A post's comments, oldest first. Each shows who wrote it (avatar and name open their profile, H12)
 * and how long ago (H13); a small ⋯ offers Delete to the comment's author and to the post's author
 * (H11). The box to add one is {@link CommentBox}, pinned under the screen.
 */
export function CommentList({ postId, postAuthorId }: { postId: string; postAuthorId: string }) {
  const { colors } = useTheme();
  const router = useRouter();
  const myId = useAuthStore((state) => state.me?.id);
  const comments = useListComments(postId, { limit: 50 });
  const refresh = useRefreshComments(postId);
  const now = useNow();
  const [menuFor, setMenuFor] = useState<CommentView | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const openProfile = (handle: string) =>
    router.push({ pathname: '/u/[handle]', params: { handle } });

  const remove = async (commentId: string) => {
    setProblem(null);
    try {
      await deleteComment(commentId);
      await refresh();
    } catch {
      setProblem('That comment could not be deleted. Try again.');
    }
  };

  return (
    <View style={styles.box}>
      <Text variant="label" muted>
        COMMENTS
      </Text>
      {problem ? (
        <Text variant="caption" style={{ color: colors.danger }} accessibilityRole="alert">
          {problem}
        </Text>
      ) : null}
      {comments.isPending ? (
        <Skeleton height={48} />
      ) : !comments.data ? (
        <StateMessage title="Could not load comments" onRetry={() => void comments.refetch()} />
      ) : comments.data.items.length === 0 ? (
        <Text muted testID="comments-empty">
          No comments yet.
        </Text>
      ) : (
        comments.data.items.map((comment) => (
          <View key={comment.id} style={styles.comment} testID="comment">
            <Pressable
              accessibilityRole="link"
              accessibilityLabel={`${comment.author.displayName}, @${comment.author.handle}`}
              hitSlop={6}
              onPress={() => openProfile(comment.author.handle)}
            >
              <Avatar
                uri={comment.author.avatarUrl}
                name={comment.author.displayName}
                size={AVATAR}
              />
            </Pressable>
            <View style={styles.commentText}>
              <View style={styles.byline}>
                <Pressable
                  accessibilityRole="link"
                  hitSlop={6}
                  onPress={() => openProfile(comment.author.handle)}
                >
                  <Text style={styles.bold}>@{comment.author.handle}</Text>
                </Pressable>
                <Text
                  variant="caption"
                  muted
                  accessibilityLabel={relativeTimeLabel(comment.createdAt, now)}
                  testID="comment-time"
                >
                  {relativeTime(comment.createdAt, now)}
                </Text>
              </View>
              <Text>{comment.body}</Text>
            </View>
            {comment.author.id === myId || postAuthorId === myId ? (
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={`More for the comment by @${comment.author.handle}`}
                hitSlop={(touchTarget - MORE) / 2}
                style={styles.more}
                onPress={() => setMenuFor(comment)}
              >
                <Ionicons name="ellipsis-horizontal" size={18} color={colors.textMuted} />
              </Pressable>
            ) : null}
          </View>
        ))
      )}
      <ActionSheet
        visible={menuFor !== null}
        onClose={() => setMenuFor(null)}
        testID="comment-menu"
        actions={
          menuFor
            ? [
                {
                  key: 'delete',
                  label: 'Delete comment',
                  icon: 'trash-outline',
                  destructive: true,
                  confirm: { title: 'Delete this comment?', confirmLabel: 'Delete' },
                  onPress: () => void remove(menuFor.id),
                },
              ]
            : []
        }
      />
    </View>
  );
}

const AVATAR = 32;
/** The ⋯ is drawn small; its touch area is still {@link touchTarget}. */
const MORE = 28;

/**
 * The box to add a comment. It sits at the foot of the post screen, outside the scrolling content, so
 * the keyboard pushes it up instead of covering it; `onSent` lets the screen scroll to the new comment.
 */
export function CommentBox({ postId, onSent }: { postId: string; onSent?: () => void }) {
  const { colors } = useTheme();
  const track = useTrack();
  const refresh = useRefreshComments(postId);
  const [body, setBody] = useState('');
  const [sending, setSending] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  // One key per thing typed: a retry of the same comment after a lost response is not posted twice.
  const [key, setKey] = useState(newUuid);

  const send = async () => {
    const text = body.trim();
    if (text === '') {
      return;
    }
    setSending(true);
    setProblem(null);
    try {
      await addComment(postId, { body: text }, { headers: { 'Idempotency-Key': key } });
      track('COMMENT', postId);
      setBody('');
      setKey(newUuid());
      await refresh();
      onSent?.();
    } catch {
      setProblem('Your comment could not be posted. Try again.');
    } finally {
      setSending(false);
    }
  };

  return (
    <View style={[styles.composer, { borderTopColor: colors.border }]}>
      {problem ? (
        <Text variant="caption" style={{ color: colors.danger }} accessibilityRole="alert">
          {problem}
        </Text>
      ) : null}
      <View style={styles.form}>
        <TextInput
          value={body}
          onChangeText={(text) => {
            // The key belongs to the text: sending the same words again is a retry (answered with the
            // first comment), different words are a new comment and need a new key.
            if (text.trim() !== body.trim()) {
              setKey(newUuid());
            }
            setBody(text);
          }}
          placeholder="Add a comment"
          placeholderTextColor={colors.textMuted}
          accessibilityLabel="Add a comment"
          maxLength={500}
          multiline
          style={[
            styles.input,
            { color: colors.text, borderColor: colors.border, backgroundColor: colors.surface },
          ]}
        />
        <Button title="Send" disabled={sending || body.trim() === ''} onPress={() => void send()} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: spacing.sm },
  comment: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm },
  commentText: { flex: 1 },
  byline: { flexDirection: 'row', alignItems: 'baseline', gap: spacing.sm },
  more: { width: MORE, height: MORE, alignItems: 'center', justifyContent: 'center' },
  bold: { fontWeight: '700' },
  composer: {
    gap: spacing.xs,
    paddingTop: spacing.sm,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  form: { flexDirection: 'row', gap: spacing.sm, alignItems: 'flex-end' },
  input: {
    flex: 1,
    minHeight: touchTarget,
    maxHeight: 120,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
  },
});
