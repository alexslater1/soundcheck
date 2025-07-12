import { useRouter } from 'expo-router';
import { useRef } from 'react';
import { ScrollView, StyleSheet, View, type TextInput } from 'react-native';

import { useGetPost } from '@/api/generated/posts/posts';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { nextPollDelay } from '@/features/player/mediaPolling';
import { spacing } from '@/theme/tokens';

import { CommentBox, CommentList } from './Comments';
import { PostCard } from './PostCard';

/**
 * One post on its own page (`/post/{id}`, the address that Share copies): the clip, its details,
 * comments, and the comment box pinned at the foot, above the keyboard when it is open. The clip,
 * details and comments scroll as one page, so the clip goes off the top; the comment
 * button scrolls to the comments and opens the keyboard in the box.
 */
export function PostScreen({ id, startAtMs }: { id: string; startAtMs?: number }) {
  const router = useRouter();
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
        <PostCard
          post={post.data}
          active
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
});
