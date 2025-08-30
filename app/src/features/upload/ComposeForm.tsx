import { useState } from 'react';
import { StyleSheet, TextInput, View } from 'react-native';

import type { UserSummary } from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { postLabel } from './gigPicker';
import { PeopleTagger } from './PeopleTagger';

/** What the form adds to a post: the caption and the people tagged in it. */
export type ComposeResult = { caption: string; taggedUserIds: string[] };

/**
 * The last part of a post: who the user was with and a caption, then Post. The Create tab draws it
 * in a step box like the parts above it, ticked once Post can be pressed. The clips (and the act in
 * each) are chosen above; one post holds them all. Post is shown at all times but stays disabled,
 * with `hint` saying what is missing, until the gig and the clips are set. When clips join
 * someone's existing post (`joining`), there is nothing to write: only Post.
 */
export function ComposeForm({
  meId,
  clips,
  hint,
  joining = false,
  onPost,
}: {
  meId: string;
  clips: number;
  /** Why Post cannot be pressed yet; null when it can. */
  hint: string | null;
  joining?: boolean;
  onPost: (result: ComposeResult) => void;
}) {
  const { colors } = useTheme();
  const [caption, setCaption] = useState('');
  const [people, setPeople] = useState<UserSummary[]>([]);

  return (
    <View style={styles.form} testID="compose-form">
      {joining ? null : (
        <>
          <PeopleTagger meId={meId} value={people} onChange={setPeople} />
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
        </>
      )}
      {hint ? (
        <Text variant="caption" muted testID="post-hint">
          {hint}
        </Text>
      ) : null}
      <Button
        title={joining ? (clips > 1 ? `Add ${clips} clips` : 'Add clip') : postLabel(clips)}
        testID="post-button"
        disabled={hint !== null}
        onPress={() => {
          onPost({ caption, taggedUserIds: people.map((person) => person.id) });
          // The Create tab stays mounted after a post, so the next one would start with this caption
          // and these people. A post that fails keeps them: its row offers Post again.
          setCaption('');
          setPeople([]);
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  form: { gap: spacing.sm },
  input: {
    minHeight: 72,
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.sm,
    textAlignVertical: 'top',
  },
});
