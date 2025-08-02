import { Image } from 'expo-image';
import { useRouter } from 'expo-router';
import { StyleSheet, View } from 'react-native';
import { z } from 'zod';

import { useUpdateMe } from '@/api/generated/me/me';
import { Button } from '@/components/ui/Button';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { Text } from '@/components/ui/Text';
import { AuthForm } from '@/features/auth/AuthForm';
import { useAuthStore } from '@/features/auth/store';
import { useChangeAvatar } from '@/features/profile/useChangeAvatar';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

const schema = z.object({
  displayName: z.string().trim().min(1, 'Enter a name').max(50, 'At most 50 characters'),
  handle: z
    .string()
    .regex(/^[a-z0-9_]{3,30}$/, '3–30 characters: a–z, 0–9 and _')
    .transform((s) => s.toLowerCase()),
  bio: z.string().max(300, 'At most 300 characters'),
});

type Values = z.infer<typeof schema>;

/** Settings → Edit profile: the photo, name, handle and bio. Me's photo and bio open it too. */
export default function EditProfileScreen() {
  const router = useRouter();
  const { colors } = useTheme();
  const photo = useChangeAvatar();
  const me = useAuthStore((s) => s.me);
  const reloadMe = useAuthStore((s) => s.reloadMe);
  const { mutateAsync: updateMe } = useUpdateMe();
  if (!me) {
    return (
      <Screen keyboard>
        <Skeleton height={240} />
      </Screen>
    );
  }
  return (
    <Screen keyboard>
      <View style={styles.photoRow}>
        <View style={[styles.avatar, { backgroundColor: colors.accent }]}>
          {me.avatarUrl ? (
            <Image
              source={{ uri: me.avatarUrl }}
              style={StyleSheet.absoluteFill}
              contentFit="cover"
            />
          ) : (
            <Text variant="title" style={{ color: colors.onAccent }}>
              {me.displayName.slice(0, 1).toUpperCase()}
            </Text>
          )}
        </View>
        <Button
          title={photo.saving ? 'Saving photo…' : me.avatarUrl ? 'Change photo' : 'Add a photo'}
          variant="secondary"
          disabled={photo.saving}
          onPress={() => void photo.change()}
        />
      </View>
      {photo.error ? (
        <Text variant="caption" style={{ color: colors.danger }}>
          {photo.error}
        </Text>
      ) : null}
      <AuthForm<Values>
        schema={schema}
        defaults={{ displayName: me.displayName, handle: me.handle, bio: me.bio ?? '' }}
        fields={[
          { name: 'displayName', label: 'Display name' },
          {
            name: 'handle',
            label: 'Handle',
            props: { autoCapitalize: 'none', autoCorrect: false },
          },
          { name: 'bio', label: 'Bio', props: { multiline: true, numberOfLines: 3 } },
        ]}
        submitLabel="Save"
        onSubmit={async (v) => {
          await updateMe({ data: { displayName: v.displayName, handle: v.handle, bio: v.bio } });
          await reloadMe();
          router.back();
        }}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  photoRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    marginBottom: spacing.md,
  },
  avatar: {
    width: 64,
    height: 64,
    borderRadius: radius.pill,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
  },
});
