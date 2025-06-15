import { useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { useUpdateMe } from '@/api/generated/me/me';
import { Chip } from '@/components/ui/Chip';
import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

/**
 * The privacy notice: what is kept, for how long, and who else sees what. Every line is a
 * fact of the code as built: the city centroid only (identity), the clip outputs without the
 * source's metadata (`FfmpegCommands`), 90 days of events (`SignalsTables`), 30 of served items, the
 * 7-day purge (`AccountPurgeService`), and images loaded from the sites that provide them.
 */
const NOTICE: { title: string; body: string }[] = [
  {
    title: 'Where you are',
    body: 'Soundcheck keeps the city you choose (its centre point), never your phone’s location. Distances are measured from that point.',
  },
  {
    title: 'Your clips',
    body: 'The file you upload is kept privately so the clip can be processed again. What other people watch is a re-encoded copy without the file’s metadata, such as the phone model and where it was filmed.',
  },
  {
    title: 'What you do in the app',
    body: 'Views, likes and how long you watch are recorded by Soundcheck itself to rank your feed, kept for 90 days (what each feed showed you, for 30). There are no advertising or third-party tracking tools.',
  },
  {
    title: 'Other services',
    body: 'Gig and artist pictures load from the ticketing and music sites that provide them, so those sites see a request from your phone, as with any web page. Setlists are looked up by Soundcheck’s server, not your phone.',
  },
  {
    title: 'Deleting your account',
    body: 'You are signed out everywhere and your profile disappears at once. Seven days later your posts, comments, clips, follows and gigs are deleted; what you did in the app stays only as anonymous counts.',
  },
];

/**
 * Settings → Privacy: whether other people see your gig stats on your profile (off until
 * the user turns it on: the stats come from the gigs marked Went, so they tell where someone was on
 * which nights; the choice shows at once and is put back if saving fails), then the privacy notice.
 */
export function PrivacyScreen() {
  const { colors } = useTheme();
  const me = useAuthStore((s) => s.me);
  const reloadMe = useAuthStore((s) => s.reloadMe);
  const { mutateAsync: updateMe } = useUpdateMe();
  const [choice, setChoice] = useState<boolean | null>(null);
  const [failed, setFailed] = useState(false);

  if (!me) {
    return (
      <Screen>
        <Text muted>Loading…</Text>
      </Screen>
    );
  }
  const shared = choice ?? me.showGigStats;
  const choose = async (show: boolean) => {
    if (show === shared) return;
    setChoice(show);
    setFailed(false);
    try {
      await updateMe({ data: { showGigStats: show } });
      await reloadMe();
    } catch {
      setFailed(true);
    }
    setChoice(null);
  };

  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.content}>
        <Text variant="label" muted>
          WHO SEES YOUR GIG STATS
        </Text>
        <View style={styles.row} accessibilityRole="radiogroup">
          <Chip label="Only me" selected={!shared} onPress={() => void choose(false)} />
          <Chip label="Everyone" selected={shared} onPress={() => void choose(true)} />
        </View>
        <Text variant="caption" muted testID="privacy-stats-detail">
          {shared
            ? 'Your profile shows the acts you have seen most and the venues you go back to, with their dates, to anyone signed in. People you block never see them.'
            : 'Your stats are on your Me tab, for you only. They come from the gigs you mark as Went, so they show where you were and when.'}
        </Text>
        {failed ? (
          <Text variant="caption" style={{ color: colors.danger }} testID="privacy-error">
            Could not save that. Check your connection and try again.
          </Text>
        ) : null}

        <Text variant="label" muted style={styles.heading}>
          PRIVACY NOTICE
        </Text>
        <View style={styles.notice} testID="privacy-notice">
          {NOTICE.map((part) => (
            <View key={part.title} style={styles.part}>
              <Text style={styles.partTitle}>{part.title}</Text>
              <Text muted>{part.body}</Text>
            </View>
          ))}
        </View>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: spacing.sm, paddingBottom: spacing.xl },
  row: { flexDirection: 'row' },
  heading: { marginTop: spacing.lg },
  notice: { gap: spacing.md },
  part: { gap: spacing.xs },
  partTitle: { fontWeight: '600' },
});
