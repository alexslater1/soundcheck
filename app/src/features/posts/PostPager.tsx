import { useCallback, useEffect } from 'react';
import { FlatList, StyleSheet, View, type ViewToken } from 'react-native';

import type { Post } from '@/api/generated/model';
import { usePlayerManager, usePlayerSlot } from '@/features/player/playerManager';
import { spacing } from '@/theme/tokens';

import { PostCard } from './PostCard';

type Props = {
  posts: Post[];
  onEndReached?: () => void;
  onGone?: (postId: string) => void;
  footer?: React.ReactElement | null;
};

/**
 * A clip is "current" when it covers at least half of the screen (or is entirely visible). A threshold of
 * "≥ 80 % visible" is one a card taller than the window (a portrait clip with its caption, in a
 * browser) can never reach: nothing would ever play. Coverage of the screen works for every size.
 */
const VIEWABILITY = { viewAreaCoveragePercentThreshold: 50 };

/**
 * The feed: posts one under another, the one in view playing. This is the component both feeds use
 * (Following now, For You later). It reports the list and the clip in view to the player manager;
 * each item then asks its slot whether it may hold a live player and whether it plays, so at most
 * three players exist however long the list is.
 */
export function PostPager({ posts, onEndReached, onGone, footer }: Props) {
  const setOrder = usePlayerManager((state) => state.setOrder);
  const setCurrent = usePlayerManager((state) => state.setCurrent);
  const reset = usePlayerManager((state) => state.reset);

  useEffect(() => {
    setOrder(posts.map((post) => post.id));
  }, [posts, setOrder]);
  // Leaving the feed stops every player.
  useEffect(() => reset, [reset]);

  // FlatList requires this callback to keep its identity; `setCurrent` (a store action) never changes.
  const onViewable = useCallback(
    ({ viewableItems }: { viewableItems: ViewToken[] }) => {
      const first = viewableItems.find((item) => item.isViewable);
      if (first) {
        setCurrent(String(first.key));
      }
    },
    [setCurrent],
  );

  const renderItem = useCallback(
    ({ item }: { item: Post }) => <PagerItem post={item} onGone={onGone} />,
    [onGone],
  );

  return (
    <FlatList
      data={posts}
      keyExtractor={(post) => post.id}
      renderItem={renderItem}
      onViewableItemsChanged={onViewable}
      viewabilityConfig={VIEWABILITY}
      onEndReached={onEndReached}
      onEndReachedThreshold={0.6}
      ItemSeparatorComponent={Separator}
      ListFooterComponent={footer}
      contentContainerStyle={styles.content}
      testID="post-pager"
    />
  );
}

function PagerItem({ post, onGone }: { post: Post; onGone?: (postId: string) => void }) {
  const { live, active } = usePlayerSlot(post.id);
  return <PostCard post={post} live={live} active={active} onGone={onGone} />;
}

function Separator() {
  return <View style={styles.separator} />;
}

const styles = StyleSheet.create({
  content: { paddingBottom: spacing.xl },
  separator: { height: spacing.xl },
});
