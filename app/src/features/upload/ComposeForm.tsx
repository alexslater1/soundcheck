import { useState } from 'react';
import { StyleSheet, TextInput, View } from 'react-native';

import { useListGigs, useListMyGigs } from '@/api/generated/catalog/catalog';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { billOf, gigChoices } from './composeDraft';
import type { PostDraft } from './types';

/**
 * The second half of creating a post: a caption, the gig the clip is from (ones the user
 * went to, then recent ones nearby), and the artists in it (the gig's bill, each one untickable).
 * The clip keeps uploading underneath; Post can be pressed before it has finished.
 */
export function ComposeForm({ onPost }: { onPost: (draft: PostDraft) => void }) {
  const { colors } = useTheme();
  // Fixed when the form opens: gigs from the last two days onwards ("recent") near the user's city.
  const [since] = useState(() => new Date(Date.now() - 2 * 24 * 60 * 60 * 1000).toISOString());
  const attended = useListMyGigs({ when: 'past', limit: 5 });
  const nearby = useListGigs({ from: since, limit: 8 });
  const [caption, setCaption] = useState('');
  const [gigId, setGigId] = useState<string | null>(null);
  const [untagged, setUntagged] = useState<string[]>([]);

  const choices = gigChoices(attended.data?.items ?? [], nearby.data?.items ?? []);
  const gig = choices.find((candidate) => candidate.id === gigId) ?? null;
  const bill = gig ? billOf(gig) : [];

  return (
    <View
      style={[styles.form, { backgroundColor: colors.surface, borderColor: colors.border }]}
      testID="compose-form"
    >
      <Text variant="label" muted>
        CAPTION
      </Text>
      <TextInput
        value={caption}
        onChangeText={setCaption}
        placeholder="Say something about it"
        placeholderTextColor={colors.textMuted}
        accessibilityLabel="Caption"
        multiline
        maxLength={2200}
        style={[styles.input, { color: colors.text, borderColor: colors.border }]}
      />
      <Text variant="label" muted>
        WHICH GIG?
      </Text>
      {attended.isPending || nearby.isPending ? (
        <Text muted>Looking for gigs…</Text>
      ) : choices.length === 0 ? (
        <Text muted testID="compose-no-gigs">
          No recent gigs near you. You can post without one.
        </Text>
      ) : (
        <View style={styles.chips} accessibilityRole="radiogroup">
          {choices.map((candidate) => (
            <Chip
              key={candidate.id}
              label={`${candidate.title} · ${candidate.venue.name}`}
              selected={candidate.id === gigId}
              onPress={() => {
                setGigId(candidate.id === gigId ? null : candidate.id);
                setUntagged([]);
              }}
            />
          ))}
        </View>
      )}
      {bill.length > 0 ? (
        <>
          <Text variant="label" muted>
            WHO IS IN IT?
          </Text>
          <View style={styles.chips}>
            {bill.map((artist) => (
              <Chip
                key={artist.id}
                label={artist.name}
                selected={!untagged.includes(artist.id)}
                onPress={() =>
                  setUntagged((current) =>
                    current.includes(artist.id)
                      ? current.filter((id) => id !== artist.id)
                      : [...current, artist.id],
                  )
                }
              />
            ))}
          </View>
        </>
      ) : null}
      <Button
        title="Post"
        testID="post-button"
        onPress={() =>
          onPost({
            caption,
            gigId,
            artistIds: bill.map((artist) => artist.id).filter((id) => !untagged.includes(id)),
          })
        }
      />
    </View>
  );
}

const styles = StyleSheet.create({
  form: { borderWidth: 1, borderRadius: radius.md, padding: spacing.md, gap: spacing.sm },
  input: {
    minHeight: 72,
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.sm,
    textAlignVertical: 'top',
  },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
});
