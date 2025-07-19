import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { useListArtists } from '@/api/generated/catalog/catalog';
import { Chip } from '@/components/ui/Chip';
import { Text } from '@/components/ui/Text';
import { TextField } from '@/components/ui/TextField';
import { useDebounced } from '@/lib/useDebounced';
import { spacing } from '@/theme/tokens';

import type { TaggedArtist } from './composeDraft';

/**
 * Who is on stage in one clip: an act from the gig's bill, another act found by name, or no
 * act (the crowd, the walk home). The post's act bar groups its clips by this.
 */
export function ActPicker({
  bill,
  value,
  onChange,
}: {
  bill: TaggedArtist[];
  value: TaggedArtist | null;
  onChange: (act: TaggedArtist | null) => void;
}) {
  const [text, setText] = useState('');
  const q = useDebounced(text.trim(), 250);
  const found = useListArtists({ q, limit: 5 }, { query: { enabled: q.length >= 2 } });
  const others = value && !bill.some((act) => act.id === value.id) ? [value] : [];

  return (
    <View style={styles.box} testID="act-picker">
      <Text variant="label" muted>
        WHO IS ON STAGE IN THIS CLIP?
      </Text>
      <View style={styles.wrap}>
        {[...bill, ...others].map((act) => (
          <Chip
            key={act.id}
            label={act.name}
            selected={value?.id === act.id}
            onPress={() => onChange(act)}
          />
        ))}
        <Chip label="No act" selected={value === null} onPress={() => onChange(null)} />
      </View>
      <TextField
        value={text}
        onChangeText={setText}
        placeholder="Another act"
        accessibilityLabel="Find another act"
        autoCorrect={false}
      />
      {q.length >= 2 ? (
        found.isError ? (
          <Text variant="caption" muted>
            Acts could not be searched just now.
          </Text>
        ) : (
          <View style={styles.wrap}>
            {(found.data?.items ?? []).map((act) => (
              <Chip
                key={act.id}
                label={`+ ${act.name}`}
                selected={false}
                onPress={() => {
                  onChange({ id: act.id, name: act.name });
                  setText('');
                }}
              />
            ))}
            {found.data && found.data.items.length === 0 ? (
              <Text variant="caption" muted>
                No act by that name.
              </Text>
            ) : null}
          </View>
        )
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: spacing.sm },
  wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
});
