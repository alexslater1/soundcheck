import Ionicons from '@expo/vector-icons/Ionicons';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { hidePost, reportPost } from '@/api/generated/engagement/engagement';
import type { Post, Reason } from '@/api/generated/model';
import { deletePost, leavePost, setPostOnProfile } from '@/api/generated/posts/posts';
import { queryClient } from '@/api/queryClient';
import { Button } from '@/components/ui/Button';
import { ActionSheet, Sheet, type SheetAction } from '@/components/ui/Sheet';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { refreshPosts } from '@/features/editPost/refreshPosts';
import { useTrack } from '@/features/telemetry/TelemetryScope';
import { forgetPost } from '@/features/upload/uploadRunner';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing, touchTarget } from '@/theme/tokens';

import { LikeButton } from './LikeButton';
import { sharePost } from './sharePost';

type Props = {
  post: Post;
  /** Called after the post was hidden or deleted, so a list can drop it. */
  onGone?: (postId: string) => void;
  /** Why the feed shows this post; adds "Why this post?" to the menu. */
  reasons?: Reason[];
  /** Icons in a row under the clip, or stacked in a column over it. */
  layout?: 'row' | 'column';
  /** True when drawn over the clip: light icons, whatever the theme. */
  onDark?: boolean;
  /**
   * What the comment button does. In the feed it opens the post with its clip playing on; on the post
   * page it goes to the comment box. Without it, the button opens the post page.
   */
  onComment?: () => void;
};

const LIGHT = '#FFFFFF';

/**
 * What can be done with a post: like, comment, share, and a ⋯ menu in a sheet (delete, after a
 * confirm, for its author; "show me less", report and "Why this post?" for everyone else). Each action
 * is also reported as telemetry.
 */
export function PostActions({
  post,
  onGone,
  reasons,
  layout = 'row',
  onDark = false,
  onComment,
}: Props) {
  const { colors } = useTheme();
  const router = useRouter();
  const track = useTrack();
  const myId = useAuthStore((state) => state.me?.id);
  const [menuOpen, setMenuOpen] = useState(false);
  const [whyOpen, setWhyOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const mine = post.author.id === myId;
  const ink = onDark ? LIGHT : colors.text;
  const column = layout === 'column';

  // Deleting a post also takes its upload off the Create list.
  const remove = () => deletePost(post.id).then(() => forgetPost(post.id));

  // The sheet has closed by the time an action runs; the outcome shows as a notice by the icons.
  const act = async (work: () => Promise<unknown>, done: string, gone: boolean) => {
    try {
      await work();
      setNotice(done);
      if (gone) {
        onGone?.(post.id);
      }
    } catch {
      setNotice('That did not work. Try again.');
    }
  };

  // What someone tagged in the post can do with their tag.
  const tagActions: SheetAction[] = post.myTag
    ? [
        {
          key: 'profile',
          label: post.myTag === 'ON_PROFILE' ? 'Take off my profile' : 'Add to my profile',
          icon: post.myTag === 'ON_PROFILE' ? 'person-remove-outline' : 'person-add-outline',
          onPress: () =>
            void act(
              () =>
                setPostOnProfile(post.id, { onProfile: post.myTag !== 'ON_PROFILE' }).then(() =>
                  refreshPosts(queryClient),
                ),
              post.myTag === 'ON_PROFILE' ? 'Taken off your profile' : 'On your profile',
              false,
            ),
        },
        ...(post.gig
          ? [
              {
                key: 'add-mine',
                label: 'Add my clips',
                icon: 'add-circle-outline',
                onPress: () =>
                  router.push({
                    pathname: '/create',
                    params: { postId: post.id, gigId: post.gig!.id },
                  }),
              } satisfies SheetAction,
            ]
          : []),
        {
          key: 'leave',
          label: 'Remove me from this post',
          icon: 'exit-outline',
          destructive: true,
          confirm: {
            title: 'Remove yourself?',
            message:
              'Your tag and any clips you added leave the post. You cannot be tagged on it again.',
            confirmLabel: 'Remove me',
          },
          onPress: () =>
            void act(
              () => leavePost(post.id).then(() => refreshPosts(queryClient)),
              'Removed from the post',
              false,
            ),
        },
      ]
    : [];

  // The ⋯ menu: one sheet of rows; the author can also edit.
  const menuActions: SheetAction[] = mine
    ? [
        {
          key: 'edit',
          label: 'Edit post',
          icon: 'create-outline',
          onPress: () => router.push({ pathname: '/edit-post/[id]', params: { id: post.id } }),
        },
        {
          key: 'delete',
          label: 'Delete post',
          icon: 'trash-outline',
          destructive: true,
          confirm: {
            title: 'Delete this post?',
            message: 'Its clips, likes and comments go with it. This cannot be undone.',
            confirmLabel: 'Delete',
          },
          onPress: () => void act(remove, 'Post deleted', true),
        },
      ]
    : [
        ...tagActions,
        ...(reasons && reasons.length > 0
          ? [
              {
                key: 'why',
                label: 'Why this post?',
                icon: 'help-circle-outline',
                onPress: () => setWhyOpen(true),
              } satisfies SheetAction,
            ]
          : []),
        {
          key: 'less',
          label: 'Show me less like this',
          icon: 'eye-off-outline',
          onPress: () =>
            void act(
              // Reported once the server has hidden it: the HIDE event also hides the post there, so
              // an event for a request that failed would hide it behind the user's back.
              () => hidePost(post.id).then(() => track('HIDE', post.id)),
              'You will see less like this',
              true,
            ),
        },
        {
          key: 'report',
          label: 'Report',
          icon: 'flag-outline',
          onPress: () =>
            void act(
              () => reportPost(post.id, { reason: 'OTHER' }).then(() => track('REPORT', post.id)),
              'Reported. Thank you.',
              false,
            ),
        },
      ];

  const menu = (
    <ActionSheet
      visible={menuOpen}
      onClose={() => setMenuOpen(false)}
      actions={menuActions}
      testID="post-menu"
    />
  );

  const why = reasons ? (
    <Sheet
      visible={whyOpen}
      onClose={() => setWhyOpen(false)}
      title="Why this post?"
      testID="why-this-post"
    >
      <View style={styles.why}>
        {reasons.map((reason) => (
          <Text key={reason.kind}>{reason.text}</Text>
        ))}
        <Button title="Close" variant="secondary" onPress={() => setWhyOpen(false)} />
      </View>
    </Sheet>
  ) : null;

  const more = (
    <Pressable
      style={column ? styles.stacked : styles.action}
      accessibilityRole="button"
      accessibilityLabel="More"
      onPress={() => setMenuOpen(true)}
    >
      <Ionicons name="ellipsis-horizontal" size={22} color={ink} />
    </Pressable>
  );

  const buttons =
    post.status === 'PUBLISHED' ? (
      <View style={column ? styles.column : styles.actions}>
        <LikeButton
          postId={post.id}
          initial={{ likedByMe: post.likedByMe, likeCount: post.likeCount }}
          onDark={onDark}
          stacked={column}
        />
        <Pressable
          style={column ? styles.stacked : styles.action}
          accessibilityRole="link"
          accessibilityLabel={`${post.commentCount} comments`}
          onPress={
            onComment ?? (() => router.push({ pathname: '/post/[id]', params: { id: post.id } }))
          }
          testID="comment-button"
        >
          <Ionicons name="chatbubble-outline" size={22} color={ink} />
          <Text testID="comment-count" style={{ color: ink }}>
            {post.commentCount}
          </Text>
        </Pressable>
        <Pressable
          style={column ? styles.stacked : styles.action}
          accessibilityRole="button"
          accessibilityLabel="Share"
          onPress={() =>
            void sharePost(post.id).then((result) => {
              // A share that failed is not a signal.
              if (result !== 'failed') {
                track('SHARE', post.id);
              }
              setNotice(
                result === 'copied'
                  ? 'Link copied'
                  : result === 'failed'
                    ? 'Could not share'
                    : null,
              );
            })
          }
        >
          <Ionicons name="share-outline" size={22} color={ink} />
        </Pressable>
        {more}
      </View>
    ) : mine ? (
      // A post that failed (or is stuck processing) has no actions; its author can still remove it.
      <View style={styles.actions}>{more}</View>
    ) : null;

  const note = notice ? (
    <Text
      variant="caption"
      muted={!onDark}
      style={onDark ? { color: LIGHT } : undefined}
      accessibilityRole="alert"
      testID="post-notice"
    >
      {notice}
    </Text>
  ) : null;

  // The menu and "Why this post?" are sheets over the whole screen. The notice sits by the icons:
  // above them over the clip, below them under it.
  return (
    <View style={column ? styles.overClip : styles.underClip}>
      {column ? note : null}
      {buttons}
      {column ? null : note}
      {menu}
      {why}
    </View>
  );
}

const styles = StyleSheet.create({
  underClip: { gap: spacing.sm },
  overClip: { gap: spacing.sm, alignItems: 'flex-end' },
  actions: { flexDirection: 'row', alignItems: 'center', gap: spacing.lg },
  column: { alignItems: 'center', gap: spacing.md },
  action: {
    minHeight: touchTarget,
    minWidth: touchTarget,
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
  },
  stacked: {
    minHeight: touchTarget,
    minWidth: touchTarget,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 2,
  },
  why: { gap: spacing.sm, paddingHorizontal: spacing.md, paddingBottom: spacing.sm },
});
