import { useState } from 'react';
import { StyleSheet, TextInput, View } from 'react-native';

import type { GigSummary } from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { ArtistTagger, type TaggedArtist } from './ArtistTagger';
import { billOf } from './composeDraft';
import { GigPicker } from './GigPicker';
import { postLabel } from './gigPicker';
import type { PostDraft } from './types';

/**
 * The second half of creating a post: a caption, the gig the clips are from
 * (suggested, searched, or added), and the artists in them (the gig's bill, untickable, plus any other
 * act). One form can post several clips from the same gig: each becomes its own post with the same
 * caption and tags. The clips keep uploading underneath; Post can be pressed before they have finished.
 */
export function ComposeForm({
  clips = 1,
  initialGig = null,
  onPost,
}: {
  clips?: number;
  initialGig?: GigSummary | null;
  onPost: (draft: PostDraft, gig: GigSummary | null) => void;
}) {
  const { colors } = useTheme();
  const [caption, setCaption] = useState('');
  const [gig, setGig] = useState<GigSummary | null>(initialGig);
  const [artists, setArtists] = useState<TaggedArtist[]>(() =>
    initialGig ? billOf(initialGig) : [],
  );

  const chooseGig = (next: GigSummary | null) => {
    setGig(next);
    // A new gig brings its own bill; acts added by hand are kept.
    setArtists((current) => {
      const oldBill = gig ? billOf(gig).map((a) => a.id) : [];
      const extras = current.filter((a) => !oldBill.includes(a.id));
      const bill = next ? billOf(next) : [];
      return [...bill, ...extras.filter((a) => !bill.some((b) => b.id === a.id))];
    });
  };

  return (
    <View
      style={[styles.form, { backgroundColor: colors.surface, borderColor: colors.border }]}
      testID="compose-form"
    >
      <Text variant="label" muted>
        WHICH GIG?
      </Text>
      <GigPicker value={gig} onChange={chooseGig} />
      <Text variant="label" muted>
        WHO IS IN {clips > 1 ? 'THEM' : 'IT'}?
      </Text>
      <ArtistTagger bill={gig ? billOf(gig) : []} value={artists} onChange={setArtists} />
      <Text variant="label" muted>
        CAPTION
      </Text>
      <TextInput
        value={caption}
        onChangeText={setCaption}
        placeholder={clips > 1 ? 'Said under each of the clips' : 'Say something about it'}
        placeholderTextColor={colors.textMuted}
        accessibilityLabel="Caption"
        multiline
        maxLength={2200}
        style={[styles.input, { color: colors.text, borderColor: colors.border }]}
      />
      {!gig ? (
        <Text variant="caption" muted>
          You can post without a gig, but then nobody finds the clip on the gig&apos;s page.
        </Text>
      ) : null}
      <Button
        title={postLabel(clips)}
        testID="post-button"
        onPress={() =>
          onPost({ caption, gigId: gig?.id ?? null, artistIds: artists.map((a) => a.id) }, gig)
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
});
