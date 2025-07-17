import { useState, type ReactNode } from 'react';
import { StyleSheet, View } from 'react-native';

import { Chip } from '@/components/ui/Chip';
import { Screen } from '@/components/ui/Screen';
import { Wordmark } from '@/components/ui/Wordmark';
import { SearchScreen } from '@/features/search/SearchScreen';
import { useTabReselect } from '@/lib/useTabReselect';
import { spacing } from '@/theme/tokens';

import { FollowingFeed } from './FollowingFeed';
import { ForYouFeed } from './ForYouFeed';

type Tab = 'following' | 'forYou' | 'search';

/**
 * Home: "For You | Following | Search". Both feeds stay mounted once opened, one over the
 * other, so switching keeps each one's place and posts with no reload; the hidden one holds
 * no players. Pressing the open feed's chip again, or the Home tab while Home is showing, asks that feed
 * for fresh posts from the top (the web has no pull to refresh). Search is a third layer,
 * mounted the first time it is opened, which keeps what was typed while the feeds are shown.
 */
export function HomeScreen() {
  const [tab, setTab] = useState<Tab>('forYou');
  // Following is fetched the first time it is opened, not with every app start.
  const [followingOpened, setFollowingOpened] = useState(false);
  const [searchOpened, setSearchOpened] = useState(false);
  const [refreshTokens, setRefreshTokens] = useState({ forYou: 0, following: 0 });
  const refresh = (which: Tab) => {
    if (which !== 'search') {
      setRefreshTokens((tokens) => ({ ...tokens, [which]: tokens[which] + 1 }));
    }
  };
  useTabReselect(() => refresh(tab));

  const choose = (which: Tab) => {
    if (which === tab) {
      refresh(which);
    }
    if (which === 'following') {
      setFollowingOpened(true);
    }
    if (which === 'search') {
      setSearchOpened(true);
    }
    setTab(which);
  };

  return (
    <Screen wide>
      <View style={styles.header}>
        <Wordmark />
      </View>
      <View style={styles.tabs} accessibilityRole="radiogroup">
        <Chip label="For You" selected={tab === 'forYou'} onPress={() => choose('forYou')} />
        <Chip
          label="Following"
          selected={tab === 'following'}
          onPress={() => choose('following')}
        />
        <Chip label="Search" selected={tab === 'search'} onPress={() => choose('search')} />
      </View>
      <View style={styles.feed}>
        <Layer shown={tab === 'forYou'} testID="feed-layer-for-you">
          <ForYouFeed refreshToken={refreshTokens.forYou} visible={tab === 'forYou'} />
        </Layer>
        {followingOpened ? (
          <Layer shown={tab === 'following'} testID="feed-layer-following">
            <FollowingFeed refreshToken={refreshTokens.following} visible={tab === 'following'} />
          </Layer>
        ) : null}
        {searchOpened ? (
          <Layer shown={tab === 'search'} testID="feed-layer-search">
            <SearchScreen />
          </Layer>
        ) : null}
      </View>
    </Screen>
  );
}

/**
 * One feed, filling the area. A hidden feed stays laid out at full size (so its list keeps its
 * scroll position) but is transparent, under the shown one, and out of reach of touches and screen
 * readers.
 */
function Layer({
  shown,
  children,
  testID,
}: {
  shown: boolean;
  children: ReactNode;
  testID: string;
}) {
  return (
    <View
      style={[StyleSheet.absoluteFill, shown ? styles.shown : styles.hidden]}
      pointerEvents={shown ? 'auto' : 'none'}
      // Out of reach of screen readers on Android and of the accessibility tree on the web.
      aria-hidden={!shown}
      testID={testID}
    >
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.sm,
  },
  tabs: { flexDirection: 'row', gap: spacing.sm, marginVertical: spacing.sm },
  feed: { flex: 1 },
  shown: { zIndex: 1 },
  hidden: { opacity: 0, zIndex: 0 },
});
