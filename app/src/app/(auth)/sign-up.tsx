import { Link } from 'expo-router';
import { z } from 'zod';

import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { AuthForm } from '@/features/auth/AuthForm';
import { useAuthStore } from '@/features/auth/store';

/** Mirrors the server rules so most mistakes are caught before a round trip. */
const schema = z.object({
  email: z.string().email('Enter a valid email'),
  handle: z
    .string()
    .regex(/^[a-z0-9_]{3,30}$/, '3–30 characters: a–z, 0–9 and _')
    .transform((s) => s.toLowerCase()),
  displayName: z.string().trim().min(1, 'Enter a name').max(50, 'At most 50 characters'),
  password: z.string().min(10, 'At least 10 characters').max(128, 'At most 128 characters'),
});

type Values = z.infer<typeof schema>;

export default function SignUpScreen() {
  const signUp = useAuthStore((s) => s.signUp);
  return (
    <Screen>
      <Text variant="display">Create account</Text>
      <AuthForm<Values>
        schema={schema}
        defaults={{ email: '', handle: '', displayName: '', password: '' }}
        fields={[
          {
            name: 'email',
            label: 'Email',
            props: { autoCapitalize: 'none', keyboardType: 'email-address', autoComplete: 'email' },
          },
          {
            name: 'handle',
            label: 'Handle',
            props: { autoCapitalize: 'none', autoCorrect: false },
          },
          { name: 'displayName', label: 'Display name' },
          {
            name: 'password',
            label: 'Password',
            props: { secureTextEntry: true, autoComplete: 'new-password' },
          },
        ]}
        submitLabel="Create account"
        onSubmit={(v) => signUp(v)}
      />
      <Link href="/sign-in">
        <Text muted>Already have an account? Sign in →</Text>
      </Link>
    </Screen>
  );
}
