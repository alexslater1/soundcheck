import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
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
import { newUuid } from '@/features/upload/uuid';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

/** A post's comments, oldest first, with a box to add one. */
export function Comments({ postId, postAuthorId }: { postId: string; postAuthorId: string }) {
  const { colors } = useTheme();
  const client = useQueryClient();
  const myId = useAuthStore((state) => state.me?.id);
  const comments = useListComments(postId, { limit: 50 });
  const [body, setBody] = useState('');
  const [sending, setSending] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  // One key per thing typed: a retry of the same comment after a lost response is not posted twice.
  const [key, setKey] = useState(newUuid);

  const refresh = async () => {
    await client.invalidateQueries({ queryKey: getListCommentsQueryKey(postId) });
    await client.invalidateQueries({ queryKey: getGetPostQueryKey(postId) });
  };

  const send = async () => {
    const text = body.trim();
    if (text === '') {
      return;
    }
    setSending(true);
    setProblem(null);
    try {
      await addComment(postId, { body: text }, { headers: { 'Idempotency-Key': key } });
      setBody('');
      setKey(newUuid());
      await refresh();
    } catch {
      setProblem('Your comment could not be posted. Try again.');
    } finally {
      setSending(false);
    }
  };

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
      <View style={styles.form}>
        <TextInput
          value={body}
          onChangeText={setBody}
          placeholder="Add a comment"
          placeholderTextColor={colors.textMuted}
          accessibilityLabel="Add a comment"
          maxLength={500}
          style={[styles.input, { color: colors.text, borderColor: colors.border }]}
        />
        <Button title="Send" disabled={sending || body.trim() === ''} onPress={() => void send()} />
      </View>
      {problem ? (
        <Text variant="caption" style={{ color: colors.danger }} accessibilityRole="alert">
          {problem}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: spacing.sm },
  comment: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  commentText: { flex: 1 },
  bold: { fontWeight: '700' },
  form: { flexDirection: 'row', gap: spacing.sm, alignItems: 'center' },
  input: {
    flex: 1,
    minHeight: touchTarget,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
  },
});
