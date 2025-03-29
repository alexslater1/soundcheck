import { useRouter } from 'expo-router';
import { ScrollView, StyleSheet, View } from 'react-native';

import { useGetPost } from '@/api/generated/posts/posts';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { nextPollDelay } from '@/features/player/mediaPolling';
import { spacing } from '@/theme/tokens';

import { Comments } from './Comments';
import { PostCard } from './PostCard';

/** One post on its own page (`/post/{id}`, the address that Share copies): the clip, its details, comments. */
export function PostScreen({ id }: { id: string }) {
  const router = useRouter();
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
  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.content}>
        <PostCard post={post.data} active onGone={() => router.back()} />
        {post.data.status === 'PUBLISHED' ? (
          <Comments postId={post.data.id} postAuthorId={post.data.author.id} />
        ) : null}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: spacing.lg, paddingBottom: spacing.xl },
});
