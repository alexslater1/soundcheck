import Ionicons from '@expo/vector-icons/Ionicons';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { bannerText, problemsOf, type UploadProblem } from './problems';
import { useUploadQueue } from './uploadQueue';
import { startUpload, submitPost } from './uploadRunner';

/** Try again: an upload continues from where it stopped; a post is sent again with its key. */
function retry(problem: UploadProblem): void {
  if (problem.kind === 'upload') {
    void startUpload(problem.localId);
    return;
  }
  const entry = useUploadQueue
    .getState()
    .entries.find((candidate) => candidate.localId === problem.localId);
  if (problem.kind === 'post' && entry?.draft) {
    void submitPost(problem.localId, entry.draft);
  }
}

/**
 * The top of the Create tab when an upload went wrong: until now the error was only in
 * the "On their way" list, below the fold. One sentence in plain words, Try again for whatever can be
 * retried, and the technical messages behind Details.
 */
export function UploadProblems({ ownerId }: { ownerId: string }) {
  const { colors } = useTheme();
  const entries = useUploadQueue((state) => state.entries);
  const [open, setOpen] = useState(false);
  const problems = problemsOf(entries, ownerId);
  if (problems.length === 0) {
    return null;
  }
  const retryable = problems.filter((problem) => problem.kind !== 'rejected');
  return (
    <View
      accessibilityRole="alert"
      style={[styles.banner, { backgroundColor: colors.surface, borderColor: colors.danger }]}
      testID="upload-problems"
    >
      <View style={styles.head}>
        <Ionicons name="alert-circle" size={20} color={colors.danger} />
        <Text style={styles.grow} testID="upload-problems-text">
          {bannerText(problems)}
        </Text>
      </View>
      <View style={styles.actions}>
        {retryable.length > 0 ? (
          <Button
            title="Try again"
            size="small"
            onPress={() => retryable.forEach(retry)}
            testID="upload-problems-retry"
          />
        ) : null}
        <Button
          title={open ? 'Hide details' : 'Details'}
          variant="secondary"
          size="small"
          onPress={() => setOpen((value) => !value)}
          testID="upload-problems-details"
        />
      </View>
      {open
        ? problems.map((problem) => (
            <Text
              key={problem.localId}
              variant="caption"
              muted
              selectable
              testID={`upload-problem-${problem.localId}`}
            >
              {problem.name}: {problem.details}
            </Text>
          ))
        : null}
    </View>
  );
}

const styles = StyleSheet.create({
  banner: { borderWidth: 1, borderRadius: radius.md, padding: spacing.md, gap: spacing.sm },
  head: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm },
  grow: { flex: 1 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
});
