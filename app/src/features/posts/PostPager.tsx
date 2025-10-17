import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  FlatList,
  Platform,
  StyleSheet,
  View,
  type LayoutChangeEvent,
  type NativeScrollEvent,
  type NativeSyntheticEvent,
  type ViewToken,
} from 'react-native';

import type { EventInSurface, Post, Reason } from '@/api/generated/model';
import { usePlayback } from '@/features/player/PlaybackScope';
import { createPlayerManager, PlayerManagerProvider } from '@/features/player/playerManager';
import { createImpressionTracker } from '@/features/telemetry/impressionTracker';
import { telemetry } from '@/features/telemetry/telemetry';
import { eventFor, TelemetryScope } from '@/features/telemetry/TelemetryScope';

import { FeedPage } from './FeedPage';
import { pageOffsets, restsOnHeader, targetIndex } from './pagerKeys';
import { REFRESH_HEADER_HEIGHT, RefreshHeader } from './RefreshHeader';
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
  /**
   * Pull to refresh (Android): pulling the first post down slides the feed over a header whose bars
   * dance while `refreshing`. The web has no pull; its feeds refresh from the tabs.
   */
  onRefresh?: () => void;
  refreshing?: boolean;
  /**
   * False while the other feed is showing in this one's place: the pager stays mounted, so it
   * keeps its place, but it holds no players, counts no impressions and takes no keys.
   */
  visible?: boolean;
  /** The page to open at (a profile's post tapped in its grid); the first by default. */
  initialIndex?: number;
};

/** An item is "current" (plays) at 80 % visible; it counts towards an impression at 50 %. */
const CURRENT_AT = { itemVisiblePercentThreshold: 80 };
const IMPRESSION_AT = { itemVisiblePercentThreshold: 50 };

const keyOf = (item: PagerItem) => item.post.id;

/**
 * The feed pager: one post per screen, snapping from one to the next. Both feeds use it,
 * For You and Following, each with its own player manager. It tells the manager which clip is in view
 * (that one plays; its neighbours hold a paused player; the rest show a poster), and it turns
 * viewability into impressions: an item at least half visible for a second is reported once. On the
 * web, ↑ / ↓ (or k / j) turn the page while the feed is on screen.
 */
export function PostPager({
  items,
  surface,
  onEndReached,
  onGone,
  onRefresh,
  refreshing = false,
  visible = true,
  initialIndex = 0,
}: Props) {
  const [manager] = useState(createPlayerManager);
  const [size, setSize] = useState({ width: 0, height: 0 });
  const list = useRef<FlatList<PagerItem>>(null);
  const { onScreen } = usePlayback();
  const header = onRefresh && Platform.OS !== 'web' ? REFRESH_HEADER_HEIGHT : 0;
  // Where the list last came to rest; it starts with the header scrolled out of sight.
  const restingAt = useRef(header);

  // Created once per pager. FlatList does not allow its viewability callbacks to change, so they
  // cannot close over props; what they need comes with each viewable item.
  const [viewability] = useState(() => {
    const seen = new Map<string, PagerItem>();
    let inView: string[] = [];
    let shown = true;
    const impressions = createImpressionTracker({
      schedule: (fn, ms) => {
        const timer = setTimeout(fn, ms);
        return () => clearTimeout(timer);
      },
      onImpression: (id) => {
        const item = seen.get(id);
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
      /** A hidden feed's posts are not being seen: its impression clocks stop until it shows again. */
      setShown(next: boolean) {
        shown = next;
        impressions.update(shown ? inView : []);
      },
      pairs: [
        {
          viewabilityConfig: CURRENT_AT,
          onViewableItemsChanged: ({ viewableItems }: { viewableItems: ViewToken[] }) => {
            const first = viewableItems.find((token) => token.isViewable);
            if (first) {
              manager.getState().setCurrent(String(first.key));
            }
          },
        },
        {
          viewabilityConfig: IMPRESSION_AT,
          onViewableItemsChanged: ({ viewableItems }: { viewableItems: ViewToken[] }) => {
            const now = viewableItems.filter((token) => token.isViewable);
            now.forEach((token) => seen.set(String(token.key), token.item as PagerItem));
            inView = now.map((token) => String(token.key));
            if (shown) {
              impressions.update(inView);
            }
          },
        },
      ],
    };
  });

  useEffect(() => {
    manager.getState().setOrder(items.map(keyOf));
  }, [items, manager]);
  useEffect(() => {
    manager.getState().setEnabled(visible);
    viewability.setShown(visible);
  }, [visible, manager, viewability]);
  // Under another screen (a profile, a post) the paused neighbours' decoders and buffers are freed:
  // with this list's players and the new screen's at once, the phone ran out (2025-10-17).
  useEffect(() => {
    manager.getState().setOnScreen(onScreen);
  }, [onScreen, manager]);
  // A new feed session is a new list: what was seen in the old one may be seen (and reported) again.
  const sessionId = items[0]?.sessionId;
  useEffect(() => {
    viewability.impressions.reset();
  }, [sessionId, viewability]);

  // The refresh is over: slide the feed back up over the header, unless the viewer has moved on.
  useEffect(() => {
    if (!refreshing && header > 0 && restingAt.current < header) {
      list.current?.scrollToOffset({ offset: header, animated: true });
      restingAt.current = header;
    }
  }, [refreshing, header]);

  const settle = (event: NativeSyntheticEvent<NativeScrollEvent>) => {
    const y = event.nativeEvent.contentOffset.y;
    restingAt.current = y;
    if (restsOnHeader(y, header) && !refreshing) {
      onRefresh?.();
    }
  };

  // The page in view is the one the player manager calls current (set by viewability above).
  const step = useCallback(
    (by: 1 | -1) => {
      const currentId = manager.getState().currentId;
      const index = targetIndex(
        items.findIndex((item) => keyOf(item) === currentId),
        by,
        items.length,
      );
      if (index !== null) {
        list.current?.scrollToIndex({ index, animated: true });
      }
    },
    [items, manager],
  );
  usePagerKeys(onScreen && visible && items.length > 0, step);

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
          // A page drawn before the pager's size settled (a screen under a page header is laid out
          // again once the header is measured) kept its first video surface at the old size: the clip
          // sat shifted, with gaps in the overlay. On the phone a new size makes a fresh page.
          key={Platform.OS === 'web' ? undefined : `${width}x${height}`}
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
  // The offsets count the refresh header above the first post.
  const getItemLayout = useCallback(
    (_: unknown, index: number) => ({ length: height, offset: header + height * index, index }),
    [height, header],
  );
  const offsets = useMemo(
    () => pageOffsets(items.length, height, header),
    [items.length, height, header],
  );
  // Opened at a page other than the first, the list starts there; the header stays out of sight.
  const [firstIndex] = useState(initialIndex);
  const start = useMemo(
    () => ({ x: 0, y: header + height * firstIndex }),
    [header, height, firstIndex],
  );

  return (
    <View style={styles.fill} onLayout={onLayout} testID="post-pager">
      {height > 0 ? (
        <PlayerManagerProvider value={manager}>
          <FlatList
            ref={list}
            data={items}
            keyExtractor={keyOf}
            renderItem={renderItem}
            getItemLayout={getItemLayout}
            extraData={size}
            ListHeaderComponent={header > 0 ? <RefreshHeader refreshing={refreshing} /> : null}
            // With a header the pages no longer start at multiples of the screen's height, so the
            // list snaps to its own offsets, one page per swipe; without one, plain paging.
            {...(header > 0
              ? { snapToOffsets: offsets, disableIntervalMomentum: true, contentOffset: start }
              : { pagingEnabled: true, snapToInterval: height, contentOffset: start })}
            initialScrollIndex={firstIndex > 0 ? firstIndex : undefined}
            snapToAlignment="start"
            decelerationRate="fast"
            showsVerticalScrollIndicator={false}
            viewabilityConfigCallbackPairs={viewability.pairs}
            onEndReached={onEndReached}
            onEndReachedThreshold={2}
            onScrollEndDrag={settle}
            onMomentumScrollEnd={settle}
            // Only a few pages exist at a time: each holds a video surface.
            initialNumToRender={2}
            maxToRenderPerBatch={2}
            windowSize={5}
            testID="post-pager-list"
          />
        </PlayerManagerProvider>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
});
