import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Pressable, StyleSheet, TextInput, View } from 'react-native';

import {
  adminListSuspectVenues,
  getAdminListSuspectVenuesQueryKey,
  useAdminFixVenueLocation,
} from '@/api/generated/admin-catalog/admin-catalog';
import type { SuspectVenue, VenueLocationFixed } from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { openWebLink } from '@/lib/openLink';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { DataTable, type Column } from '../DataTable';
import { problemLines } from '../ranking/configModel';
import { inUk, osmUrl, parseLatLng, reasonLabel } from './venueModel';

/**
 * Suspect venue locations: fan-added venues, venues at a town's centre point and venues with no
 * point. "Fix location…" takes a latitude and longitude (pasted from OpenStreetMap), shows the point on
 * OpenStreetMap before saving, and the server moves the venue and every gig there.
 */
export function VenueLocations() {
  const { colors } = useTheme();
  const client = useQueryClient();
  const [editing, setEditing] = useState<string | null>(null);
  const [fixed, setFixed] = useState<{ name: string; result: VenueLocationFixed } | null>(null);
  const params = { suspect: true };
  const query = useInfiniteQuery({
    queryKey: [...getAdminListSuspectVenuesQueryKey(params), 'infinite'],
    queryFn: ({ pageParam, signal }) =>
      adminListSuspectVenues(
        { ...params, ...(pageParam ? { cursor: pageParam } : {}) },
        { signal },
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
  const rows = query.data?.pages.flatMap((p) => p.items);

  const columns: Column<SuspectVenue>[] = [
    {
      key: 'venue',
      title: 'Venue',
      flex: 3,
      sortValue: (v) => v.name,
      render: (v) => (
        <View>
          <Text>{v.name}</Text>
          <Text variant="caption" muted>
            {[v.city, v.postcode].filter(Boolean).join(' · ') || 'No address'}
          </Text>
        </View>
      ),
    },
    {
      key: 'reasons',
      title: 'Why',
      flex: 2,
      render: (v) => <Text variant="caption">{v.reasons.map(reasonLabel).join(' · ')}</Text>,
    },
    {
      key: 'gigs',
      title: 'Gigs',
      align: 'right',
      sortValue: (v) => v.gigs,
      render: (v) => <Text>{v.gigs}</Text>,
    },
    {
      key: 'where',
      title: 'Now at',
      flex: 2,
      render: (v) =>
        v.location && v.osmUrl ? (
          <Pressable accessibilityRole="link" onPress={() => void openWebLink(v.osmUrl)}>
            <Text variant="caption" style={{ color: colors.accent }}>
              {v.location.lat.toFixed(4)}, {v.location.lng.toFixed(4)} ↗
            </Text>
          </Pressable>
        ) : (
          <Text variant="caption" muted>
            Nowhere
          </Text>
        ),
    },
    {
      key: 'actions',
      title: '',
      flex: 1.2,
      render: (v) => (
        <Button
          title={editing === v.id ? 'Close' : 'Fix location…'}
          size="small"
          variant="secondary"
          onPress={() => setEditing(editing === v.id ? null : v.id)}
          testID={`venue-fix-${v.id}`}
        />
      ),
    },
  ];

  return (
    <View style={styles.page} testID="venue-locations">
      <Text variant="label" muted>
        SUSPECT LOCATIONS
      </Text>
      <Text muted>
        A venue a fan added sits at its town&apos;s centre until a source gives its real point, and
        no source ever moves a point once set. Put these where they really are; their gigs move too.
      </Text>
      {fixed ? (
        <View testID="venue-fixed">
          <Text style={{ color: colors.success }}>
            Moved {fixed.name} and {fixed.result.gigsMoved} gig
            {fixed.result.gigsMoved === 1 ? '' : 's'}.
          </Text>
          <Pressable accessibilityRole="link" onPress={() => void openWebLink(fixed.result.osmUrl)}>
            <Text style={{ color: colors.accent }}>See it on OpenStreetMap ↗</Text>
          </Pressable>
        </View>
      ) : null}
      <DataTable
        testID="suspect-venues-table"
        columns={columns}
        rows={rows}
        rowKey={(v) => v.id}
        status={query.status}
        onRetry={() => void query.refetch()}
        emptyTitle="No suspect venues"
        emptyDetail="Every venue has a point that a source supplied or an operator checked."
        hasMore={query.hasNextPage}
        loadingMore={query.isFetchingNextPage}
        onLoadMore={() => void query.fetchNextPage()}
        renderBelow={(v) =>
          editing === v.id ? (
            <LocationForm
              venue={v}
              onFixed={(result) => {
                setEditing(null);
                setFixed({ name: v.name, result });
                void client.invalidateQueries({ queryKey: getAdminListSuspectVenuesQueryKey() });
              }}
            />
          ) : null
        }
      />
    </View>
  );
}

/** Latitude and longitude for one venue, checked against the UK box before it is sent. */
export function LocationForm({
  venue,
  onFixed,
}: {
  venue: SuspectVenue;
  onFixed: (result: VenueLocationFixed) => void;
}) {
  const { colors } = useTheme();
  const [text, setText] = useState('');
  const fix = useAdminFixVenueLocation({ mutation: { onSuccess: onFixed } });
  const point = parseLatLng(text);
  const valid = point !== null && inUk(point);
  return (
    <View style={styles.form} testID={`venue-form-${venue.id}`}>
      <TextInput
        accessibilityLabel={`Latitude, longitude for ${venue.name}`}
        placeholder="Latitude, longitude, e.g. 53.4631, -2.2913"
        placeholderTextColor={colors.textMuted}
        value={text}
        onChangeText={setText}
        testID={`venue-form-${venue.id}-input`}
        style={[
          styles.input,
          { color: colors.text, borderColor: colors.border, backgroundColor: colors.surface },
        ]}
      />
      {text.trim() && point === null ? (
        <Text variant="caption" style={{ color: colors.danger }}>
          Two numbers, latitude first.
        </Text>
      ) : null}
      {point && !inUk(point) ? (
        <Text variant="caption" style={{ color: colors.danger }}>
          That point is outside the UK. Latitude comes first.
        </Text>
      ) : null}
      {valid ? (
        <Pressable
          accessibilityRole="link"
          onPress={() => void openWebLink(osmUrl(point.lat, point.lng))}
        >
          <Text variant="caption" style={{ color: colors.accent }}>
            Check the point on OpenStreetMap ↗
          </Text>
        </Pressable>
      ) : null}
      {fix.isError ? (
        <Text variant="caption" style={{ color: colors.danger }}>
          {problemLines(fix.error).join(' · ')}
        </Text>
      ) : null}
      <Button
        title={`Move it and its ${venue.gigs} gig${venue.gigs === 1 ? '' : 's'}`}
        size="small"
        disabled={!valid || fix.isPending}
        onPress={() => point && fix.mutate({ id: venue.id, data: point })}
        testID={`venue-form-${venue.id}-save`}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  page: { gap: spacing.md },
  form: { gap: spacing.sm, padding: spacing.md, maxWidth: 520 },
  input: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    fontSize: 16,
  },
});
