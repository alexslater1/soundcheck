import { z } from 'zod';

import { deleteMe } from '@/api/generated/me/me';
import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { AuthForm } from '@/features/auth/AuthForm';
import { useAuthStore } from '@/features/auth/store';

const schema = z.object({ password: z.string().min(1, 'Enter your password') });

type Values = z.infer<typeof schema>;

/** Delete account with password confirmation (purged after 7 days). */
export default function AccountScreen() {
  const clear = useAuthStore((s) => s.clear);
  return (
    <Screen>
      <Text variant="title">Delete your account</Text>
      <Text muted>
        Your profile disappears immediately and everything is purged after 7 days. Enter your
        password to confirm.
      </Text>
      <AuthForm<Values>
        schema={schema}
        defaults={{ password: '' }}
        fields={[{ name: 'password', label: 'Password', props: { secureTextEntry: true } }]}
        submitLabel="Delete account"
        onSubmit={async (v) => {
          await deleteMe({ password: v.password });
          clear(); // the root layout redirects to sign-in
        }}
      />
    </Screen>
  );
}
