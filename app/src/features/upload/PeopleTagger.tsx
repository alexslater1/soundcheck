import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import type { UserSummary } from '@/api/generated/model';
import { useListFollowing } from '@/api/generated/social/social';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { Text } from '@/components/ui/Text';
import { TextField } from '@/components/ui/TextField';
import { spacing } from '@/theme/tokens';

/** People a post can tag (the server's limit). */
export const MAX_TAGGED = 10;

/** Shown before anything is typed. */
const FIRST = 12;

/**
 * "Who were you with?": the people the user follows, the ones they tag ticked. Only people they
 * follow can be tagged (the server checks); typing narrows the list by name or handle.
 */
export function PeopleTagger({
  meId,
  value,
  onChange,
}: {
  meId: string;
  value: UserSummary[];
  onChange: (people: UserSummary[]) => void;
}) {
  const following = useListFollowing(meId, { limit: 50 });
  const [text, setText] = useState('');
  const tagged = new Set(value.map((person) => person.id));
  const toggle = (person: UserSummary) =>
    onChange(
      tagged.has(person.id)
        ? value.filter((p) => p.id !== person.id)
        : value.length < MAX_TAGGED
          ? [...value, person]
          : value,
    );

  const q = text.trim().toLowerCase();
  const people = (following.data?.items ?? []).filter(
    (person) =>
      q === '' ||
      person.handle.toLowerCase().includes(q) ||
      person.displayName.toLowerCase().includes(q),
  );
  const shown = [
    ...value,
    ...people.filter((person) => !tagged.has(person.id)).slice(0, q === '' ? FIRST : people.length),
  ];

  return (
    <View style={styles.box} testID="people-tagger">
      <Text variant="label" muted>
        WHO WERE YOU WITH?
      </Text>
      {following.isPending ? (
        <Text muted>Loading the people you follow…</Text>
      ) : following.isError ? (
        <View style={styles.box}>
          <Text muted>The people you follow could not be loaded.</Text>
          <Button
            title="Try again"
            size="small"
            variant="secondary"
            onPress={() => void following.refetch()}
          />
        </View>
      ) : (following.data?.items.length ?? 0) === 0 ? (
        <Text variant="caption" muted testID="people-empty">
          Follow the people you go to gigs with to tag them.
        </Text>
      ) : (
        <>
          <TextField
            value={text}
            onChangeText={setText}
            placeholder="Find someone you follow"
            accessibilityLabel="Find someone to tag"
            autoCorrect={false}
            autoCapitalize="none"
          />
          <View style={styles.wrap}>
            {shown.map((person) => (
              <Chip
                key={person.id}
                label={`@${person.handle}`}
                selected={tagged.has(person.id)}
                disabled={!tagged.has(person.id) && value.length >= MAX_TAGGED}
                onPress={() => toggle(person)}
              />
            ))}
          </View>
          {value.length >= MAX_TAGGED ? (
            <Text variant="caption" muted>
              A post can tag up to {MAX_TAGGED} people.
            </Text>
          ) : null}
        </>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: spacing.sm },
  wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
});
