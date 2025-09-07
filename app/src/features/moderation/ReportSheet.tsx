import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import type { ReportRequest, ReportRequestReason } from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { Sheet } from '@/components/ui/Sheet';
import { Text } from '@/components/ui/Text';
import { TextField } from '@/components/ui/TextField';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

/** The six reasons the server accepts, in the order the sheet offers them. */
export const REPORT_REASONS: readonly { id: ReportRequestReason; label: string }[] = [
  { id: 'SPAM', label: 'Spam' },
  { id: 'ABUSE', label: 'Abuse' },
  { id: 'NUDITY', label: 'Nudity' },
  { id: 'VIOLENCE', label: 'Violence' },
  { id: 'COPYRIGHT', label: 'Copyright' },
  { id: 'OTHER', label: 'Other' },
];

/** The server keeps at most this many characters of what the reporter writes. */
export const DETAILS_MAX = 500;

export const THANKS = 'Thanks, a moderator will take a look';

type Phase = 'form' | 'sending' | 'done';

/**
 * Reporting a post, a post comment or a gig comment: pick a reason, add a few words if you
 * like, send. The sheet then thanks the reporter; sending the same report twice changes nothing on the
 * server. `onSubmit` makes the call for the kind of thing reported; a rejection keeps the form open with
 * a message, so it can be sent again.
 */
export function ReportSheet({
  visible,
  what,
  onSubmit,
  onClose,
  onReported,
}: {
  visible: boolean;
  /** What is reported, for the heading: "post", "comment". */
  what: string;
  onSubmit: (body: ReportRequest) => Promise<unknown>;
  onClose: () => void;
  /** Called once the report was accepted (telemetry, a notice). */
  onReported?: () => void;
}) {
  const { colors } = useTheme();
  const [reason, setReason] = useState<ReportRequestReason | null>(null);
  const [details, setDetails] = useState('');
  const [phase, setPhase] = useState<Phase>('form');
  const [problem, setProblem] = useState<string | null>(null);

  // Every way out starts the next report afresh.
  const close = () => {
    setReason(null);
    setDetails('');
    setPhase('form');
    setProblem(null);
    onClose();
  };

  const send = async () => {
    if (!reason) return;
    setPhase('sending');
    setProblem(null);
    const text = details.trim();
    try {
      await onSubmit(text ? { reason, details: text } : { reason });
      setPhase('done');
      onReported?.();
    } catch {
      setPhase('form');
      setProblem('Your report could not be sent. Try again.');
    }
  };

  return (
    <Sheet visible={visible} onClose={close} title={`Report ${what}`} testID="report-sheet">
      {phase === 'done' ? (
        <View style={styles.body} testID="report-done">
          <Text accessibilityRole="alert">{THANKS}</Text>
          <Button title="Close" variant="secondary" onPress={close} />
        </View>
      ) : (
        <View style={styles.body}>
          <Text muted>Why are you reporting this {what}?</Text>
          <View style={styles.reasons} role="radiogroup" accessibilityLabel="Reason">
            {REPORT_REASONS.map((r) => (
              <Chip
                key={r.id}
                label={r.label}
                selected={reason === r.id}
                onPress={() => setReason(r.id)}
                disabled={phase === 'sending'}
              />
            ))}
          </View>
          <TextField
            value={details}
            onChangeText={setDetails}
            placeholder="Anything a moderator should know (optional)"
            accessibilityLabel="Details"
            maxLength={DETAILS_MAX}
            multiline
            editable={phase !== 'sending'}
            style={styles.details}
            testID="report-details"
          />
          <Text variant="caption" muted style={styles.count}>
            {`${details.length}/${DETAILS_MAX}`}
          </Text>
          {problem ? (
            <Text variant="caption" style={{ color: colors.danger }} accessibilityRole="alert">
              {problem}
            </Text>
          ) : null}
          <View style={styles.buttons}>
            <Button
              title="Cancel"
              variant="secondary"
              onPress={close}
              disabled={phase === 'sending'}
            />
            <Button
              title={phase === 'sending' ? 'Sending…' : 'Report'}
              variant="danger"
              onPress={() => void send()}
              disabled={!reason || phase === 'sending'}
              testID="report-send"
            />
          </View>
        </View>
      )}
    </Sheet>
  );
}

const styles = StyleSheet.create({
  body: { gap: spacing.sm, paddingHorizontal: spacing.md, paddingBottom: spacing.sm },
  reasons: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  details: { minHeight: 88, paddingVertical: spacing.sm, textAlignVertical: 'top' },
  count: { alignSelf: 'flex-end' },
  buttons: { flexDirection: 'row', justifyContent: 'flex-end', gap: spacing.sm },
});
