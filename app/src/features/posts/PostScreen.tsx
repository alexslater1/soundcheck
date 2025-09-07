import { useRouter } from 'expo-router';
import { useRef } from 'react';
import { ScrollView, StyleSheet, View, type TextInput } from 'react-native';

import { useGetPost } from '@/api/generated/posts/posts';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { nextPollDelay } from '@/features/player/mediaPolling';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { CommentBox, CommentList } from './Comments';
import { PostCard } from './PostCard';

/**
 * One post on its own page (`/post/{id}`, the address that Share copies): the clip, its details,
 * comments, and the comment box pinned at the foot, above the keyboard when it is open. The clip,
 * details and comments scroll as one page, so the clip goes off the top; the comment
 * button scrolls to the comments and opens the keyboard in the box.
 */
export function PostScreen({
  id,
  clipId,
  startAtMs,
}: {
  id: string;
  /** The clip to start on: the one in view in the feed, or a shared link's. */
  clipId?: string;
  startAtMs?: number;
}) {
  const router = useRouter();
  const { colors } = useTheme();
  const scroll = useRef<ScrollView>(null);
  const commentsAt = useRef(0);
  const commentInput = useRef<TextInput>(null);
  const post = useGetPost(id, {
    query: {
      // The author may open their post while the clip is still being transcoded: keep asking.
      refetchInterval: (query) =>
        query.state.status === 'error' || query.state.data?.status !== 'PENDING_MEDIA'
          ? false
          : nextPollDelay('PROCESSING', query.state.dataUpdateCount),
    },
  });

  if (post.isPending) {
    return (
      <Screen>
        <View testID="post-loading">
          <Skeleton height={420} />
        </View>
      </Screen>
    );
  }
  if (!post.data) {
    return (
      <Screen>
        <StateMessage
          testID="post-error"
          title="This post is not available"
          detail="It may have been deleted, or it is not published yet."
          onRetry={() => void post.refetch()}
        />
      </Screen>
    );
  }
  const published = post.data.status === 'PUBLISHED';
  const goToComments = () => {
    scroll.current?.scrollTo({ y: commentsAt.current, animated: true });
    commentInput.current?.focus();
  };
  return (
    // No padding above the scroll view: the page's top padding scrolls with it instead of staying
    // behind as a band the content slides under.
    <Screen keyboard style={styles.flush}>
      <ScrollView
        ref={scroll}
        contentContainerStyle={styles.content}
        keyboardShouldPersistTaps="handled"
        testID="post-scroll"
      >
        {post.data.status === 'HIDDEN' ? (
          // Only the author can open a hidden post: say why nobody else sees it.
          <View
            style={[
              styles.banner,
              { backgroundColor: colors.surfaceRaised, borderColor: colors.warning },
            ]}
            accessibilityRole="alert"
            testID="post-hidden-banner"
          >
            <Text style={styles.bannerTitle}>Hidden by moderators</Text>
            <Text variant="caption" muted>
              After reports, a moderator took this post out of every feed and list. Only you can see
              it.
            </Text>
          </View>
        ) : null}
        <PostCard
          post={post.data}
          active
          clipId={clipId}
          startAtMs={startAtMs}
          onComment={published ? goToComments : undefined}
          onGone={() => (router.canGoBack() ? router.back() : router.replace('/'))}
        />
        {published ? (
          <View
            onLayout={(event) => {
              commentsAt.current = event.nativeEvent.layout.y;
            }}
          >
            <CommentList postId={post.data.id} postAuthorId={post.data.author.id} />
          </View>
        ) : null}
      </ScrollView>
      {published ? (
        <CommentBox
          postId={post.data.id}
          inputRef={commentInput}
          onSent={() => scroll.current?.scrollToEnd({ animated: true })}
        />
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  flush: { paddingTop: 0 },
  content: { gap: spacing.lg, paddingTop: spacing.md, paddingBottom: spacing.lg },
  banner: { borderWidth: 1, borderRadius: radius.md, padding: spacing.md, gap: spacing.xs },
  bannerTitle: { fontWeight: '700' },
});
