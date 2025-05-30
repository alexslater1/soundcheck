import { useRouter } from 'expo-router';
import { z } from 'zod';

import { useUpdateMe } from '@/api/generated/me/me';
import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { AuthForm } from '@/features/auth/AuthForm';
import { useAuthStore } from '@/features/auth/store';

const schema = z.object({
  displayName: z.string().trim().min(1, 'Enter a name').max(50, 'At most 50 characters'),
  handle: z
    .string()
    .regex(/^[a-z0-9_]{3,30}$/, '3–30 characters: a–z, 0–9 and _')
    .transform((s) => s.toLowerCase()),
  bio: z.string().max(300, 'At most 300 characters'),
});

type Values = z.infer<typeof schema>;

export default function EditProfileScreen() {
  const router = useRouter();
  const me = useAuthStore((s) => s.me);
  const reloadMe = useAuthStore((s) => s.reloadMe);
  const { mutateAsync: updateMe } = useUpdateMe();
  if (!me) {
    return (
      <Screen keyboard>
        <Text muted>Loading…</Text>
      </Screen>
    );
  }
  return (
    <Screen keyboard>
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
