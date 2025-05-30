import { useRouter } from 'expo-router';
import { useRef } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

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
 * comments, and the comment box pinned at the foot, above the keyboard when it is open.
 */
export function PostScreen({ id }: { id: string }) {
  const router = useRouter();
  const scroll = useRef<ScrollView>(null);
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
  return (
    <Screen keyboard>
      <ScrollView
        ref={scroll}
        contentContainerStyle={styles.content}
        keyboardShouldPersistTaps="handled"
      >
        <PostCard
          post={post.data}
          active
          onGone={() => (router.canGoBack() ? router.back() : router.replace('/'))}
        />
        {published ? (
          <CommentList postId={post.data.id} postAuthorId={post.data.author.id} />
        ) : null}
      </ScrollView>
      {published ? (
        <CommentBox
          postId={post.data.id}
          onSent={() => scroll.current?.scrollToEnd({ animated: true })}
        />
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: spacing.lg, paddingBottom: spacing.lg },
});
