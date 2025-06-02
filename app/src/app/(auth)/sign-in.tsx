import { Link } from 'expo-router';
import { ScrollView, StyleSheet, View } from 'react-native';
import { z } from 'zod';

import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { Wordmark } from '@/components/ui/Wordmark';
import { AuthForm } from '@/features/auth/AuthForm';
import { currentPassword, signInEmail } from '@/features/auth/autofill';
import { useAuthStore } from '@/features/auth/store';
import { spacing } from '@/theme/tokens';

const schema = z.object({
  email: z.string().email('Enter a valid email'),
  password: z.string().min(1, 'Enter your password'),
});

type Values = z.infer<typeof schema>;

/** Sign-in. The root layout redirects once the store says signedIn. */
export default function SignInScreen() {
  const signIn = useAuthStore((s) => s.signIn);
  return (
    <Screen keyboard>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <View style={styles.brand}>
          <Wordmark size={30} />
        </View>
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
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: spacing.md, paddingBottom: spacing.xl },
  brand: { marginTop: spacing.lg, marginBottom: spacing.md },
});
