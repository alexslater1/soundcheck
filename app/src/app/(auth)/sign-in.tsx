import { Link } from 'expo-router';
import { z } from 'zod';

import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { AuthForm } from '@/features/auth/AuthForm';
import { currentPassword, signInEmail } from '@/features/auth/autofill';
import { useAuthStore } from '@/features/auth/store';

const schema = z.object({
  email: z.string().email('Enter a valid email'),
  password: z.string().min(1, 'Enter your password'),
});

type Values = z.infer<typeof schema>;

/** Sign-in. The root layout redirects once the store says signedIn. */
export default function SignInScreen() {
  const signIn = useAuthStore((s) => s.signIn);
  return (
    <Screen>
      <Text variant="display">Welcome back</Text>
      <Text muted>Sign in to see gigs near you and clips from the crowd.</Text>
      <AuthForm<Values>
        schema={schema}
        defaults={{ email: '', password: '' }}
        fields={[
          {
            name: 'email',
            label: 'Email',
            props: signInEmail,
          },
          {
            name: 'password',
            label: 'Password',
            props: currentPassword,
          },
        ]}
        submitLabel="Sign in"
        onSubmit={(v) => signIn(v.email, v.password)}
      />
      <Link href="/sign-up">
        <Text muted>New here? Create an account →</Text>
      </Link>
    </Screen>
  );
}
