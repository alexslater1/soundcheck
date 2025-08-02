import { useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { useUpdateMe } from '@/api/generated/me/me';
import type { MeResponseProfileVisibility } from '@/api/generated/model';
import { Chip } from '@/components/ui/Chip';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

/**
 * The privacy notice: what is kept, for how long, and who else sees what. Every line is a
 * fact of the code as built: the city centroid only (identity), and the approximate position sent
 * per request for distances and never kept, the clip outputs without the
 * source's metadata (`FfmpegCommands`), 90 days of events (`SignalsTables`), 30 of served items, the
 * 7-day purge (`AccountPurgeService`), and images loaded from the sites that provide them.
 */
const NOTICE: { title: string; body: string }[] = [
  {
    title: 'Where you are',
    body: 'Soundcheck keeps the city you choose (its centre point), never your phone’s location. If you allow location, the app sends your approximate position (to about a kilometre) with the gig lists, so distances are measured from where you are; it is used for that answer only and not kept. Otherwise distances are measured from your city’s centre.',
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
 * Settings → Privacy: who sees your profile. Public (the default): everyone signed in sees your
 * clips, gig stats and gig history. Private: only your followers do, and new followers need your
 * OK (a follow request in Activity); everyone else sees your name and picture, and a gig page
 * counts your clips without showing them. One switch for everything (it replaced a stats-only
 * setting). The choice shows at once, one save at a time, and is put back if saving fails. Then the
 * privacy notice.
 */
export function PrivacyScreen() {
  const { colors } = useTheme();
  const me = useAuthStore((s) => s.me);
  const reloadMe = useAuthStore((s) => s.reloadMe);
  const { mutateAsync: updateMe } = useUpdateMe();
  // The choice being saved, shown at once; null when nothing is being saved. One save at a time: the
  // chips wait while one is on its way, so the server always ends on the last choice made.
  const [choice, setChoice] = useState<MeResponseProfileVisibility | null>(null);
  const [failed, setFailed] = useState(false);

  if (!me) {
    return (
      <Screen>
        <Skeleton height={120} />
      </Screen>
    );
  }
  const visibility = choice ?? me.profileVisibility;
  const saving = choice !== null;
  const choose = async (next: MeResponseProfileVisibility) => {
    if (saving || next === visibility) return;
    setChoice(next);
    setFailed(false);
    try {
      await updateMe({ data: { profileVisibility: next } });
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
          WHO SEES YOUR PROFILE
        </Text>
        <View style={styles.row} accessibilityRole="radiogroup">
          <Chip
            label="Everyone"
            selected={visibility === 'PUBLIC'}
            disabled={saving}
            onPress={() => void choose('PUBLIC')}
          />
          <Chip
            label="My followers"
            selected={visibility === 'PRIVATE'}
            disabled={saving}
            onPress={() => void choose('PRIVATE')}
          />
        </View>
        <Text variant="caption" muted testID="privacy-profile-detail">
          {visibility === 'PRIVATE'
            ? 'Only your followers see your clips, your gig stats, the gigs you went to and who you follow. New followers need your OK: their requests show in Activity. The people who already follow you stay; remove anyone from your Followers list. Everyone else sees your name and picture, and a gig page counts your clips without showing them or your name. Switching back to Everyone accepts the requests still waiting.'
            : 'Anyone signed in sees your clips, your gig stats and the gigs you went to (they show where you were and when), and anyone can follow you. People you block never see anything.'}
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
