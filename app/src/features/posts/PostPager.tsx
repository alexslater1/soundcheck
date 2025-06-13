import { useCallback, useEffect, useRef, useState } from 'react';
import { FlatList, StyleSheet, View, type LayoutChangeEvent, type ViewToken } from 'react-native';

import type { EventInSurface, Post, Reason } from '@/api/generated/model';
import { usePlayback } from '@/features/player/PlaybackScope';
import { usePlayerManager } from '@/features/player/playerManager';
import { createImpressionTracker } from '@/features/telemetry/impressionTracker';
import { telemetry } from '@/features/telemetry/telemetry';
import { eventFor, TelemetryScope } from '@/features/telemetry/TelemetryScope';

import { FeedPage } from './FeedPage';
import { targetIndex } from './pagerKeys';
import { usePagerKeys } from './usePagerKeys';

/** A post in a feed: where it stands, and (in the ranked feed) why it is there and in which session. */
export type PagerItem = {
  post: Post;
  position: number;
  reasons?: Reason[];
  sessionId?: string;
};

type Props = {
  items: PagerItem[];
  /** Which feed this is; every event from inside it carries it. */
  surface: EventInSurface;
  onEndReached?: () => void;
  onGone?: (postId: string) => void;
  /** Pull to refresh (Android). */
  onRefresh?: () => void;
  refreshing?: boolean;
};

/** An item is "current" (plays) at 80 % visible; it counts towards an impression at 50 %. */
const CURRENT_AT = { itemVisiblePercentThreshold: 80 };
const IMPRESSION_AT = { itemVisiblePercentThreshold: 50 };

const keyOf = (item: PagerItem) => item.post.id;

/**
 * The feed pager: one post per screen, snapping from one to the next. Both feeds use it,
 * For You and Following. It tells the player manager which clip is in view (that one plays; its
 * neighbours hold a paused player; the rest show a poster), and it turns viewability into impressions:
 * an item at least half visible for a second is reported once. On the web, ↑ / ↓ (or k / j) turn the
 * page while the feed is on screen.
 */
export function PostPager({ items, surface, onEndReached, onGone, onRefresh, refreshing }: Props) {
  const setOrder = usePlayerManager((state) => state.setOrder);
  const reset = usePlayerManager((state) => state.reset);
  const [size, setSize] = useState({ width: 0, height: 0 });
  const list = useRef<FlatList<PagerItem>>(null);
  const { onScreen } = usePlayback();

  // Created once per pager. FlatList does not allow its viewability callbacks to change, so they
  // cannot close over props; what they need comes with each viewable item.
  const [viewability] = useState(() => {
    const visible = new Map<string, PagerItem>();
    const impressions = createImpressionTracker({
      schedule: (fn, ms) => {
        const timer = setTimeout(fn, ms);
        return () => clearTimeout(timer);
      },
      onImpression: (id) => {
        const item = visible.get(id);
        if (item) {
          telemetry.track(
            eventFor(
              { surface, sessionId: item.sessionId, position: item.position },
              'IMPRESSION',
              id,
              undefined,
              new Date(),
            ),
          );
        }
      },
    });
    return {
      impressions,
      pairs: [
        {
          viewabilityConfig: CURRENT_AT,
          onViewableItemsChanged: ({ viewableItems }: { viewableItems: ViewToken[] }) => {
            const first = viewableItems.find((token) => token.isViewable);
            if (first) {
              usePlayerManager.getState().setCurrent(String(first.key));
            }
          },
        },
        {
          viewabilityConfig: IMPRESSION_AT,
          onViewableItemsChanged: ({ viewableItems }: { viewableItems: ViewToken[] }) => {
            const now = viewableItems.filter((token) => token.isViewable);
            now.forEach((token) => visible.set(String(token.key), token.item as PagerItem));
            impressions.update(now.map((token) => String(token.key)));
          },
        },
      ],
    };
  });

  useEffect(() => {
    setOrder(items.map(keyOf));
  }, [items, setOrder]);
  // Leaving the feed stops every player.
  useEffect(() => reset, [reset]);
  // A new feed session is a new list: what was seen in the old one may be seen (and reported) again.
  const sessionId = items[0]?.sessionId;
  useEffect(() => {
    viewability.impressions.reset();
  }, [sessionId, viewability]);

  // The page in view is the one the player manager calls current (set by viewability above).
  const step = useCallback(
    (by: 1 | -1) => {
      const currentId = usePlayerManager.getState().currentId;
      const index = targetIndex(
        items.findIndex((item) => keyOf(item) === currentId),
        by,
        items.length,
      );
      if (index !== null) {
        list.current?.scrollToIndex({ index, animated: true });
      }
    },
    [items],
  );
  usePagerKeys(onScreen && items.length > 0, step);

  const onLayout = useCallback((event: LayoutChangeEvent) => {
    const { width, height } = event.nativeEvent.layout;
    setSize((current) =>
      current.width === width && current.height === height ? current : { width, height },
    );
  }, []);

  const { width, height } = size;
  const renderItem = useCallback(
    ({ item }: { item: PagerItem }) => (
      <TelemetryScope place={{ surface, sessionId: item.sessionId, position: item.position }}>
        <FeedPage
          post={item.post}
          reasons={item.reasons}
          width={width}
          height={height}
          onGone={onGone}
        />
      </TelemetryScope>
    ),
    [surface, width, height, onGone],
  );
  // Every page is exactly one screen tall, so the list can jump to any of them without measuring.
  const getItemLayout = useCallback(
    (_: unknown, index: number) => ({ length: height, offset: height * index, index }),
    [height],
  );

  return (
    <View style={styles.fill} onLayout={onLayout} testID="post-pager">
      {height > 0 ? (
        <FlatList
          ref={list}
          data={items}
          keyExtractor={keyOf}
          renderItem={renderItem}
          getItemLayout={getItemLayout}
          extraData={size}
          pagingEnabled
          snapToInterval={height}
          snapToAlignment="start"
          decelerationRate="fast"
          showsVerticalScrollIndicator={false}
          viewabilityConfigCallbackPairs={viewability.pairs}
          onEndReached={onEndReached}
          onEndReachedThreshold={2}
          onRefresh={onRefresh}
          refreshing={onRefresh ? (refreshing ?? false) : undefined}
          // Only a few pages exist at a time: each holds a video surface.
          initialNumToRender={2}
          maxToRenderPerBatch={2}
          windowSize={5}
          testID="post-pager-list"
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
});
