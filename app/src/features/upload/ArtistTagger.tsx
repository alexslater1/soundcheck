import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { addArtist, useListArtists } from '@/api/generated/catalog/catalog';
import { Chip } from '@/components/ui/Chip';
import { Text } from '@/components/ui/Text';
import { TextField } from '@/components/ui/TextField';
import { useDebounced } from '@/lib/useDebounced';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

export type TaggedArtist = { id: string; name: string };

/**
 * "Who is in it?": the gig's bill, each act ticked by default and untickable, plus any
 * other act found by name, or added when the catalog does not know it (a support act no listing
 * mentioned). `value` is the list of tagged acts.
 */
export function ArtistTagger({
  bill,
  value,
  onChange,
}: {
  bill: TaggedArtist[];
  value: TaggedArtist[];
  onChange: (artists: TaggedArtist[]) => void;
}) {
  const { colors } = useTheme();
  const [text, setText] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const q = useDebounced(text.trim(), 250);
  const found = useListArtists({ q, limit: 5 }, { query: { enabled: q.length >= 2 } });

  const tagged = new Set(value.map((a) => a.id));
  const extras = value.filter((a) => !bill.some((b) => b.id === a.id));
  const toggle = (artist: TaggedArtist) =>
    onChange(tagged.has(artist.id) ? value.filter((a) => a.id !== artist.id) : [...value, artist]);
  const add = (artist: TaggedArtist) => {
    if (!tagged.has(artist.id)) {
      onChange([...value, artist]);
    }
    setText('');
  };
  const addNew = async () => {
    setProblem(null);
    try {
      const added = await addArtist({ name: text.trim() });
      add({ id: added.item.id, name: added.item.name });
    } catch {
      setProblem('That act could not be added. Try again.');
    }
  };

  return (
    <View style={styles.box}>
      <View style={styles.wrap}>
        {[...bill, ...extras].map((artist) => (
          <Chip
            key={artist.id}
            label={artist.name}
            selected={tagged.has(artist.id)}
            onPress={() => toggle(artist)}
          />
        ))}
      </View>
      <TextField
        value={text}
        onChangeText={setText}
        placeholder="Tag another act"
        accessibilityLabel="Tag another act"
        autoCorrect={false}
      />
      {q.length >= 2 ? (
        <View style={styles.wrap}>
          {(found.data?.items ?? [])
            .filter((a) => !tagged.has(a.id))
            .map((a) => (
              <Chip
                key={a.id}
                label={`+ ${a.name}`}
                selected={false}
                onPress={() => add({ id: a.id, name: a.name })}
              />
            ))}
          <Chip
            label={`+ New act “${text.trim()}”`}
            selected={false}
            onPress={() => void addNew()}
          />
        </View>
      ) : null}
      {problem ? (
        <Text variant="caption" style={{ color: colors.danger }}>
          {problem}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: spacing.sm },
  wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
});
