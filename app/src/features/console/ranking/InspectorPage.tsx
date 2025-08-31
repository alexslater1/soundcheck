import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import {
  adminFeedProfile,
  adminFeedServed,
  getAdminFeedProfileQueryKey,
  getAdminFeedServedQueryKey,
} from '@/api/generated/admin-feed/admin-feed';
import { useAdminListRankingConfigs } from '@/api/generated/admin-ranking/admin-ranking';
import type {
  FeedAffinity,
  FeedProfile,
  RankingPreviewItem,
  ServedSession,
} from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { problemLines } from './configModel';
import { PreviewColumn } from './PreviewColumn';
import { usePreview } from './usePreview';
import { UserPicker, type PickedUser } from './UserPicker';
import { WaterfallDrawer } from './WaterfallDrawer';

type Tab = 'now' | 'shown';

/**
 * The feed inspector: one user's feed as ranking sees it. Their taste (genre
 * vector, strongest artist and author affinities); "Would see now", the active config's ranking of
 * their candidates with each item's sources and contributions; and "Was shown", the sessions they were
 * actually served, item by item with what they did.
 */
export default function InspectorPage() {
  const router = useRouter();
  const params = useLocalSearchParams<{ user?: string; tab?: string }>();
  const userId = params.user ?? null;
  const tab: Tab = params.tab === 'shown' ? 'shown' : 'now';

  const profile = useQuery({
    queryKey: getAdminFeedProfileQueryKey(userId ?? 'none'),
    queryFn: ({ signal }) => adminFeedProfile(userId as string, { signal }),
    enabled: userId !== null,
  });
  const picked: PickedUser | null =
    userId === null
      ? null
      : { id: userId, handle: profile.data?.user.handle ?? userId.slice(0, 8) };

  return (
    <ScrollView contentContainerStyle={styles.page} testID="inspector-page">
      <Text variant="display">Feed inspector</Text>
      <UserPicker
        selected={picked}
        onPick={(u) => router.setParams({ user: u.id })}
        testID="inspector-user"
      />
      {userId === null ? (
        <StateMessage
          testID="inspector-no-user"
          title="Pick a user"
          detail="See what ranking knows about them, what they'd see now, and what they were shown."
        />
      ) : profile.status === 'pending' ? (
        <View testID="inspector-loading" style={styles.loading}>
          <Skeleton height={120} />
          <Skeleton height={320} />
        </View>
      ) : profile.status === 'error' ? (
        <StateMessage
          testID="inspector-error"
          title="Couldn't load this user"
          detail={problemLines(profile.error).join(' · ')}
          onRetry={() => void profile.refetch()}
        />
      ) : (
        <>
          <ProfileCard profile={profile.data} />
          <View style={styles.tabs} role="tablist">
            {(
              [
                ['now', 'Would see now'],
                ['shown', 'Was shown'],
              ] as const
            ).map(([id, label]) => (
              <TabButton
                key={id}
                label={label}
                active={tab === id}
                onPress={() => router.setParams({ tab: id })}
                testID={`inspector-tab-${id}`}
              />
            ))}
          </View>
          {tab === 'now' ? <WouldSeeNow userId={userId} /> : <WasShown userId={userId} />}
        </>
      )}
    </ScrollView>
  );
}

function ProfileCard({ profile }: { profile: FeedProfile }) {
  const { colors } = useTheme();
  const max = Math.max(0.0001, ...profile.genres.map((g) => g.weight));
  return (
    <View
      style={[styles.card, { borderColor: colors.border, backgroundColor: colors.surface }]}
      testID="inspector-profile"
    >
      <Text variant="title">
        @{profile.user.handle} · {profile.user.displayName}
      </Text>
      <Text variant="caption" muted>
        Follows {profile.followingCount} people and {profile.followedArtistCount} artists
      </Text>
      <View style={styles.profileRow}>
        <View style={styles.profileCol}>
          <Text variant="label" muted>
            GENRES (CHOSEN 0.6 + ENGAGED 0.4)
          </Text>
          {profile.genres.length === 0 ? (
            <Text variant="caption" muted>
              No genres chosen and no engagement yet.
            </Text>
          ) : (
            profile.genres.map((g) => (
              <View key={g.id} style={styles.bar} testID={`genre-${g.id}`}>
                <Text variant="caption" style={styles.barLabel} numberOfLines={1}>
                  {g.name}
                </Text>
                <View style={[styles.barTrack, { backgroundColor: colors.surfaceRaised }]}>
                  <View
                    style={{
                      width: `${(g.weight / max) * 100}%`,
                      height: '100%',
                      backgroundColor: colors.accent,
                      borderRadius: radius.sm,
                    }}
                  />
                </View>
                <Text variant="caption" style={styles.barValue}>
                  {g.weight.toFixed(2)}
                </Text>
              </View>
            ))
          )}
        </View>
        <AffinityList title="ARTISTS" items={profile.artists} empty="No artist affinity yet." />
        <AffinityList title="AUTHORS" items={profile.authors} empty="No author affinity yet." />
      </View>
    </View>
  );
}

function AffinityList({
  title,
  items,
  empty,
}: {
  title: string;
  items: readonly FeedAffinity[];
  empty: string;
}) {
  return (
    <View style={styles.profileCol}>
      <Text variant="label" muted>
        {title}
      </Text>
      {items.length === 0 ? (
        <Text variant="caption" muted>
          {empty}
        </Text>
      ) : (
        items.map((a) => (
          <Text key={a.id} variant="caption" numberOfLines={1}>
            {a.score.toFixed(2)} · {a.name}
            {a.followed ? ' · followed' : ''}
          </Text>
        ))
      )}
    </View>
  );
}

function WouldSeeNow({ userId }: { userId: string }) {
  const configs = useAdminListRankingConfigs();
  const active = configs.data?.find((c) => c.status === 'ACTIVE');
  const preview = usePreview(
    active ? { userId, configA: { id: active.id }, limit: 50, exploration: false } : null,
  );
  const [focus, setFocus] = useState<RankingPreviewItem | null>(null);

  if (configs.status === 'error' || preview.status === 'error') {
    return (
      <StateMessage
        testID="inspector-now-error"
        title="Couldn't rank this feed"
        detail={problemLines(preview.error ?? configs.error).join(' · ')}
        onRetry={() => void (configs.status === 'error' ? configs.refetch() : preview.refetch())}
      />
    );
  }
  if (!preview.data || !active) {
    return <Skeleton height={420} />;
  }
  return (
    <View style={styles.now} testID="inspector-now">
      <View style={styles.nowList}>
        <Text variant="caption" muted>
          {preview.data.candidates} candidates, ranked by the active config (v{active.version}),
          without exploration. Click a row for its features and contributions.
        </Text>
        <PreviewColumn
          title={`Would see now (v${active.version})`}
          items={preview.data.a.items}
          showChange={false}
          selectedPostId={focus?.post.id ?? null}
          onSelect={setFocus}
          testID="inspector-now-list"
        />
      </View>
      {focus ? (
        <WaterfallDrawer
          item={focus}
          configLabel={`Active (v${active.version})`}
          onClose={() => setFocus(null)}
        />
      ) : null}
    </View>
  );
}

function WasShown({ userId }: { userId: string }) {
  const served = useInfiniteQuery({
    queryKey: [...getAdminFeedServedQueryKey(userId), 'infinite'],
    queryFn: ({ pageParam, signal }) =>
      adminFeedServed(
        userId,
        { limit: 5, ...(pageParam ? { cursor: pageParam } : {}) },
        { signal },
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
  if (served.status === 'pending') return <Skeleton height={320} />;
  if (served.status === 'error') {
    return (
      <StateMessage
        testID="inspector-shown-error"
        title="Couldn't load the served sessions"
        onRetry={() => void served.refetch()}
      />
    );
  }
  const sessions = served.data.pages.flatMap((p) => p.items);
  if (sessions.length === 0) {
    return (
      <StateMessage
        testID="inspector-shown-empty"
        title="Never shown a For You feed"
        detail="Served items are kept for 30 days."
      />
    );
  }
  return (
    <View style={styles.sessions} testID="inspector-shown">
      {sessions.map((s) => (
        <SessionCard key={s.sessionId} session={s} />
      ))}
      {served.hasNextPage ? (
        <Button
          title={served.isFetchingNextPage ? 'Loading…' : 'Older sessions'}
          variant="secondary"
          size="small"
          disabled={served.isFetchingNextPage}
          onPress={() => void served.fetchNextPage()}
          testID="inspector-shown-more"
        />
      ) : null}
    </View>
  );
}

function SessionCard({ session }: { session: ServedSession }) {
  const { colors } = useTheme();
  return (
    <View
      style={[styles.card, { borderColor: colors.border, backgroundColor: colors.surface }]}
      testID={`session-${session.sessionId}`}
    >
      <Text>
        {session.startedAt.slice(0, 16).replace('T', ' ')} UTC · config v
        {session.configVersion ?? '?'}
      </Text>
      <Text variant="caption" muted>
        {session.shown} shown · {session.positives} positive · {session.negatives} negative
      </Text>
      {session.entries.map((e) => (
        <View key={`${e.position}-${e.postId}`} style={styles.entry}>
          <Text variant="caption" style={styles.position}>
            {e.position}
          </Text>
          <Text variant="caption" style={styles.entryText} numberOfLines={1}>
            {e.post ? `@${e.post.author.handle}` : 'post no longer visible'}
            {e.post?.artists.length ? ` · ${e.post.artists.map((a) => a.name).join(', ')}` : ''}
          </Text>
          {e.exploration ? (
            <Text variant="label" style={{ color: colors.accent2 }}>
              EXPLORE
            </Text>
          ) : null}
          <Text variant="caption" style={styles.score}>
            {e.score.toFixed(3)}
          </Text>
          <Text
            variant="label"
            style={[
              styles.outcome,
              {
                color: e.negative ? colors.danger : e.positive ? colors.success : colors.textMuted,
              },
            ]}
          >
            {e.negative ? 'NEGATIVE' : e.positive ? 'POSITIVE' : '—'}
          </Text>
          <Text variant="caption" muted style={styles.watch}>
            {e.maxWatchFraction != null ? `watched ${Math.round(e.maxWatchFraction * 100)} %` : ''}
          </Text>
        </View>
      ))}
    </View>
  );
}

function TabButton({
  label,
  active,
  onPress,
  testID,
}: {
  label: string;
  active: boolean;
  onPress: () => void;
  testID: string;
}) {
  const { colors } = useTheme();
  return (
    <Pressable
      role="tab"
      aria-selected={active}
      onPress={onPress}
      testID={testID}
      style={[
        styles.tab,
        { borderColor: active ? colors.accent : colors.border },
        active ? { backgroundColor: colors.accentSoft } : null,
      ]}
    >
      <Text variant="caption">{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  page: { gap: spacing.md, paddingBottom: spacing.xl },
  loading: { gap: spacing.md },
  card: { borderWidth: 1, borderRadius: radius.md, padding: spacing.md, gap: spacing.xs },
  profileRow: { flexDirection: 'row', gap: spacing.lg, marginTop: spacing.sm },
  profileCol: { flex: 1, gap: 4, minWidth: 0 },
  bar: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  barLabel: { width: 110 },
  barTrack: { flex: 1, height: 10, borderRadius: radius.sm },
  barValue: { width: 40, textAlign: 'right' },
  tabs: { flexDirection: 'row', gap: spacing.xs },
  tab: {
    borderWidth: 1,
    borderRadius: radius.pill,
    paddingHorizontal: spacing.md,
    paddingVertical: 4,
  },
  now: { flexDirection: 'row', gap: spacing.md, alignItems: 'flex-start' },
  nowList: { flex: 1, gap: spacing.sm, maxWidth: 720 },
  sessions: { gap: spacing.md },
  entry: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  position: { width: 24, textAlign: 'right' },
  entryText: { flex: 1 },
  score: { width: 48, textAlign: 'right' },
  outcome: { width: 72 },
  watch: { width: 90 },
});
