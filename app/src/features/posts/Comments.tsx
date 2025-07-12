import { useQueryClient } from '@tanstack/react-query';
import { useState, type Ref } from 'react';
import { StyleSheet, TextInput, View } from 'react-native';

import {
  addComment,
  deleteComment,
  getListCommentsQueryKey,
  useListComments,
} from '@/api/generated/engagement/engagement';
import { getGetPostQueryKey } from '@/api/generated/posts/posts';
import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { useTrack } from '@/features/telemetry/TelemetryScope';
import { newUuid } from '@/features/upload/uuid';
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

/** A post's comments, oldest first. The box to add one is {@link CommentBox}, pinned under the screen. */
export function CommentList({ postId, postAuthorId }: { postId: string; postAuthorId: string }) {
  const myId = useAuthStore((state) => state.me?.id);
  const comments = useListComments(postId, { limit: 50 });
  const refresh = useRefreshComments(postId);

  return (
    <View style={styles.box}>
      <Text variant="label" muted>
        COMMENTS
      </Text>
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
            <View style={styles.commentText}>
              <Text style={styles.bold}>@{comment.author.handle}</Text>
              <Text>{comment.body}</Text>
            </View>
            {comment.author.id === myId || postAuthorId === myId ? (
              <Button
                title="Delete"
                variant="secondary"
                accessibilityLabel={`Delete comment by ${comment.author.handle}`}
                onPress={() => void deleteComment(comment.id).then(refresh, () => undefined)}
              />
            ) : null}
          </View>
        ))
      )}
    </View>
  );
}

/**
 * The box to add a comment. It sits at the foot of the post screen, outside the scrolling content, so
 * the keyboard pushes it up instead of covering it; `onSent` lets the screen scroll to the new comment,
 * and `inputRef` lets the post's comment button focus the box.
 */
export function CommentBox({
  postId,
  onSent,
  inputRef,
}: {
  postId: string;
  onSent?: () => void;
  inputRef?: Ref<TextInput>;
}) {
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
          ref={inputRef}
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
  comment: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  commentText: { flex: 1 },
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
