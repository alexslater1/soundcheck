import { useState } from 'react';
import { StyleSheet, TextInput, View } from 'react-native';

import type { GigSummary } from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { ArtistTagger, type TaggedArtist } from './ArtistTagger';
import { billOf } from './composeDraft';
import { postLabel } from './gigPicker';
import type { PostDraft } from './types';

/**
 * The last step of creating a post: who is in the clips (the
 * chosen gig's bill, untickable, plus any other act) and a caption. The gig is chosen first, above this
 * form. One form posts several clips from that gig: each becomes its own post with the same caption and
 * tags. The clips keep preparing and uploading underneath; Post can be pressed before they have finished.
 */
export function ComposeForm({
  clips = 1,
  gig,
  onPost,
}: {
  clips?: number;
  gig: GigSummary | null;
  onPost: (draft: PostDraft) => void;
}) {
  const { colors } = useTheme();
  const [caption, setCaption] = useState('');
  const [artists, setArtists] = useState<TaggedArtist[]>(() => (gig ? billOf(gig) : []));
  // The gig can still be changed above: its bill replaces the old one, acts added by hand stay. (State
  // adjusted while rendering, as React recommends for "reset some state when a prop changes".)
  const [billGig, setBillGig] = useState<GigSummary | null>(gig);
  if (billGig?.id !== gig?.id) {
    const oldBill = billGig ? billOf(billGig).map((a) => a.id) : [];
    const extras = artists.filter((a) => !oldBill.includes(a.id));
    const bill = gig ? billOf(gig) : [];
    setArtists([...bill, ...extras.filter((a) => !bill.some((b) => b.id === a.id))]);
    setBillGig(gig);
  }

  return (
    <View
      style={[styles.form, { backgroundColor: colors.surface, borderColor: colors.border }]}
      testID="compose-form"
    >
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
      <Button
        title={postLabel(clips)}
        testID="post-button"
        onPress={() =>
          onPost({ caption, gigId: gig?.id ?? null, artistIds: artists.map((a) => a.id) })
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
