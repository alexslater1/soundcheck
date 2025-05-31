import Ionicons from '@expo/vector-icons/Ionicons';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { hidePost, reportPost } from '@/api/generated/engagement/engagement';
import type { Post, Reason } from '@/api/generated/model';
import { deletePost } from '@/api/generated/posts/posts';
import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { useTrack } from '@/features/telemetry/TelemetryScope';
import { forgetPost } from '@/features/upload/uploadRunner';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

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
};

const LIGHT = '#FFFFFF';

/**
 * What can be done with a post: like, comment, share, and a menu (delete for its author; "show me
 * less", report and "Why this post?" for everyone else). Each action is also reported as telemetry.
 */
export function PostActions({ post, onGone, reasons, layout = 'row', onDark = false }: Props) {
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

  const act = async (work: () => Promise<unknown>, done: string, gone: boolean) => {
    setMenuOpen(false);
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

  const panel = StyleSheet.flatten([
    styles.menu,
    column
      ? { backgroundColor: colors.surface, padding: spacing.sm, borderRadius: radius.md }
      : null,
  ]);

  const menu = menuOpen ? (
    <View style={panel} testID="post-menu">
      {mine ? (
        <Button
          title="Delete post"
          variant="danger"
          onPress={() => void act(remove, 'Post deleted', true)}
        />
      ) : (
        <>
          {reasons && reasons.length > 0 ? (
            <Button
              title="Why this post?"
              variant="secondary"
              onPress={() => {
                setMenuOpen(false);
                setWhyOpen(true);
              }}
            />
          ) : null}
          <Button
            title="Show me less like this"
            variant="secondary"
            onPress={() =>
              void act(
                // Reported once the server has hidden it: the HIDE event also hides the post there, so
                // an event for a request that failed would hide it behind the user's back.
                () => hidePost(post.id).then(() => track('HIDE', post.id)),
                'You will see less like this',
                true,
              )
            }
          />
          <Button
            title="Report"
            variant="secondary"
            onPress={() =>
              void act(
                () => reportPost(post.id, { reason: 'OTHER' }).then(() => track('REPORT', post.id)),
                'Reported. Thank you.',
                false,
              )
            }
          />
        </>
      )}
    </View>
  ) : null;

  const why =
    whyOpen && reasons ? (
      <View style={panel} testID="why-this-post" accessibilityRole="summary">
        <Text variant="label" muted>
          WHY THIS POST
        </Text>
        {reasons.map((reason) => (
          <Text key={reason.kind}>{reason.text}</Text>
        ))}
        <Button title="Close" variant="secondary" onPress={() => setWhyOpen(false)} />
      </View>
    ) : null;

  const buttons =
    post.status === 'PUBLISHED' ? (
      <View style={column ? styles.column : styles.actions}>
        <LikeButton
          key={`${post.id}-${post.likedByMe}-${post.likeCount}`}
          postId={post.id}
          initial={{ likedByMe: post.likedByMe, likeCount: post.likeCount }}
          onDark={onDark}
          stacked={column}
        />
        <Pressable
          style={column ? styles.stacked : styles.action}
          accessibilityRole="link"
          accessibilityLabel={`${post.commentCount} comments`}
          onPress={() => router.push({ pathname: '/post/[id]', params: { id: post.id } })}
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
        <Pressable
          style={column ? styles.stacked : styles.action}
          accessibilityRole="button"
          accessibilityLabel="More"
          onPress={() => setMenuOpen((open) => !open)}
        >
          <Ionicons name="ellipsis-horizontal" size={22} color={ink} />
        </Pressable>
      </View>
    ) : mine ? (
      // A post that failed (or is stuck processing) has no actions; its author can still remove it.
      <View style={styles.actions}>
        <Button
          title="Delete post"
          variant="danger"
          onPress={() => void act(remove, 'Post deleted', true)}
        />
      </View>
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

  // Over the clip the panels open above the icons; under it, below them.
  return column ? (
    <View style={styles.overClip}>
      {why}
      {menu}
      {note}
      {buttons}
    </View>
  ) : (
    <View style={styles.underClip}>
      {buttons}
      {menu}
      {why}
      {note}
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
  menu: { gap: spacing.sm, maxWidth: 280 },
});
