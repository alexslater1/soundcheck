import Ionicons from '@expo/vector-icons/Ionicons';
import { useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useState, type Ref } from 'react';
import { Pressable, StyleSheet, TextInput, View } from 'react-native';

import {
  addComment,
  deleteComment,
  getListCommentsQueryKey,
  useListComments,
} from '@/api/generated/engagement/engagement';
import type { UserSummary } from '@/api/generated/model';
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

/** A comment in any thread: a post's (always with its author) or a gig's. */
export type ThreadComment = {
  id: string;
  /** Null for a gig comment from a private profile the viewer does not follow. */
  author?: UserSummary | null;
  body?: string | null;
  createdAt: string;
  /** Written by a private profile the viewer does not follow, so only that it is there shows. */
  fromPrivateProfile?: boolean;
};

/**
 * A thread of comments, oldest first, shared by posts and gigs. Each shows who wrote it (avatar and
 * name open their profile) and how long ago; a small ⋯ offers Delete where `canDelete` allows, and
 * (H11). A private profile's comment the viewer may not read shows as "A private profile commented".
 */
export function CommentThread({
  title,
  comments,
  canDelete,
  onDelete,
  empty,
  testID,
}: {
  title: string;
  comments: {
    isPending: boolean;
    data?: { items: ThreadComment[] };
    refetch: () => unknown;
  };
  canDelete: (comment: ThreadComment) => boolean;
  /** Deletes the comment and refreshes the thread; a rejection shows a message. */
  onDelete: (commentId: string) => Promise<unknown>;
  empty: string;
  testID?: string;
}) {
  const { colors } = useTheme();
  const router = useRouter();
  const now = useNow();
  const [menuFor, setMenuFor] = useState<ThreadComment | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const openProfile = (handle: string) =>
    router.push({ pathname: '/u/[handle]', params: { handle } });

  const remove = async (commentId: string) => {
    setProblem(null);
    try {
      await onDelete(commentId);
    } catch {
      setProblem('That comment could not be deleted. Try again.');
    }
  };

  return (
    <View style={styles.box} testID={testID}>
      <Text variant="label" muted>
        {title.toUpperCase()}
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
          {empty}
        </Text>
      ) : (
        comments.data.items.map((comment) => {
          const author = comment.author;
          const time = (
            <Text
              variant="caption"
              muted
              accessibilityLabel={relativeTimeLabel(comment.createdAt, now)}
              testID="comment-time"
            >
              {relativeTime(comment.createdAt, now)}
            </Text>
          );
          if (!author) {
            return (
              <View key={comment.id} style={styles.comment} testID="comment-private">
                <View
                  style={[styles.lock, { backgroundColor: colors.surfaceRaised }]}
                  accessibilityElementsHidden
                  importantForAccessibility="no-hide-descendants"
                >
                  <Ionicons name="lock-closed-outline" size={16} color={colors.textMuted} />
                </View>
                <View style={[styles.commentText, styles.byline]}>
                  <Text muted>A private profile commented</Text>
                  {time}
                </View>
              </View>
            );
          }
          return (
            <View key={comment.id} style={styles.comment} testID="comment">
              <Pressable
                accessibilityRole="link"
                accessibilityLabel={`${author.displayName}, @${author.handle}`}
                hitSlop={6}
                onPress={() => openProfile(author.handle)}
              >
                <Avatar uri={author.avatarUrl} name={author.displayName} size={AVATAR} />
              </Pressable>
              <View style={styles.commentText}>
                <View style={styles.byline}>
                  <Pressable
                    accessibilityRole="link"
                    hitSlop={6}
                    onPress={() => openProfile(author.handle)}
                  >
                    <Text style={styles.bold}>@{author.handle}</Text>
                  </Pressable>
                  {time}
                </View>
                <Text>{comment.body}</Text>
              </View>
              {canDelete(comment) ? (
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel={`More for the comment by @${author.handle}`}
                  hitSlop={(touchTarget - MORE) / 2}
                  style={styles.more}
                  onPress={() => setMenuFor(comment)}
                >
                  <Ionicons name="ellipsis-horizontal" size={18} color={colors.textMuted} />
                </Pressable>
              ) : null}
            </View>
          );
        })
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

/**
 * A post's comments (H11-H13): the comment's author and the post's author can delete one. The box to
 * add one is {@link CommentBox}, pinned under the screen.
 */
export function CommentList({ postId, postAuthorId }: { postId: string; postAuthorId: string }) {
  const myId = useAuthStore((state) => state.me?.id);
  const comments = useListComments(postId, { limit: 50 });
  const refresh = useRefreshComments(postId);
  return (
    <CommentThread
      title="Comments"
      comments={comments}
      canDelete={(comment) => comment.author?.id === myId || postAuthorId === myId}
      onDelete={async (commentId) => {
        await deleteComment(commentId);
        await refresh();
      }}
      empty="No comments yet."
    />
  );
}

const AVATAR = 32;
/** The ⋯ is drawn small; its touch area is still {@link touchTarget}. */
const MORE = 28;

/**
 * The box to add a comment to any thread. It keeps one Idempotency-Key per text, so a retry of the
 * same comment after a lost response is not posted twice; `inputRef` lets a button focus the box.
 */
export function CommentComposer({
  onSend,
  onSent,
  inputRef,
  placeholder = 'Add a comment',
  pinned = true,
}: {
  /** Posts the text with the key; rejects when it could not be posted. */
  onSend: (text: string, idempotencyKey: string) => Promise<unknown>;
  onSent?: () => void;
  inputRef?: Ref<TextInput>;
  placeholder?: string;
  /** At the foot of a screen, with a rule above it (the post page); false inside a page (a gig's). */
  pinned?: boolean;
}) {
  const { colors } = useTheme();
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
      await onSend(text, key);
      setBody('');
      setKey(newUuid());
      onSent?.();
    } catch {
      setProblem('Your comment could not be posted. Try again.');
    } finally {
      setSending(false);
    }
  };

  return (
    <View style={[styles.composer, pinned ? { borderTopColor: colors.border } : styles.inline]}>
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
          placeholder={placeholder}
          placeholderTextColor={colors.textMuted}
          accessibilityLabel={placeholder}
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

/**
 * The box to add a comment to a post. It sits at the foot of the post screen, outside the scrolling
 * content, so the keyboard pushes it up instead of covering it; `onSent` lets the screen scroll to the
 * new comment, and `inputRef` lets the post's comment button focus the box.
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
  const track = useTrack();
  const refresh = useRefreshComments(postId);
  return (
    <CommentComposer
      inputRef={inputRef}
      onSent={onSent}
      onSend={async (text, key) => {
        await addComment(postId, { body: text }, { headers: { 'Idempotency-Key': key } });
        track('COMMENT', postId);
        await refresh();
      }}
    />
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
  inline: { borderTopWidth: 0, paddingTop: 0 },
  lock: {
    width: AVATAR,
    height: AVATAR,
    borderRadius: AVATAR / 2,
    alignItems: 'center',
    justifyContent: 'center',
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
