import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import type { NotificationView } from '@/api/generated/model';
import { getListNotificationsQueryKey } from '@/api/generated/notifications/notifications';
import { acceptFollowRequest, declineFollowRequest } from '@/api/generated/social/social';
import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

type Answer = 'accepted' | 'declined';

/**
 * Accept and Decline on a follow request in Activity. Shown while the request is still
 * waiting (`requestPending`). The answer shows at once; then the inbox is read again, where an accepted
 * request has become "started following you" and a declined one is gone. Answering also marks the entry
 * read (`onAnswered`), so the badge counts one fewer.
 */
export function FollowRequestActions({
  notification,
  onAnswered,
}: {
  notification: NotificationView;
  onAnswered: () => void;
}) {
  const { colors } = useTheme();
  const client = useQueryClient();
  const [answer, setAnswer] = useState<Answer | null>(null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const actor = notification.actor;
  if (!actor || !notification.requestPending) return null;

  async function respond(next: Answer) {
    if (!actor) return;
    setBusy(true);
    setFailed(false);
    try {
      await (next === 'accepted' ? acceptFollowRequest(actor.id) : declineFollowRequest(actor.id));
      setAnswer(next);
      onAnswered();
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
      void client.invalidateQueries({ queryKey: getListNotificationsQueryKey() });
    }
  }

  if (answer) {
    return (
      <Text variant="caption" muted testID="follow-request-answer">
        {answer === 'accepted' ? 'Accepted' : 'Declined'}
      </Text>
    );
  }
  return (
    <View style={styles.actions}>
      <Button
        title="Accept"
        size="small"
        disabled={busy}
        accessibilityLabel={`Accept @${actor.handle}'s follow request`}
        onPress={() => void respond('accepted')}
        testID="follow-request-accept"
      />
      <Button
        title="Decline"
        size="small"
        variant="secondary"
        disabled={busy}
        accessibilityLabel={`Decline @${actor.handle}'s follow request`}
        onPress={() => void respond('declined')}
        testID="follow-request-decline"
      />
      {failed ? (
        <Text variant="caption" style={{ color: colors.danger }}>
          Try again
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  actions: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
});
