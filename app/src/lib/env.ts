import { z } from 'zod';

/**
 * Public runtime configuration. `EXPO_PUBLIC_*` variables are inlined at build time by Expo.
 * Every request — including media URLs returned by the API — is absolute against `apiBaseUrl`.
 * Dev: http://localhost:8088 (the Caddy edge; Android uses `adb reverse tcp:8088 tcp:8088`). AWS: the CloudFront domain.
 */
const envSchema = z.object({
  // Trimmed before the URL check, and without the tabs/newlines the URL parser ignores.
  apiBaseUrl: z
    .string()
    .trim()
    .url()
    .transform((value) => value.replace(/[\t\n\r]/g, '').replace(/\/+$/, '')),
});

export type Env = z.infer<typeof envSchema>;

export function parseEnv(source: Record<string, string | undefined>): Env {
  return envSchema.parse({
    apiBaseUrl: source.EXPO_PUBLIC_API_BASE_URL ?? 'http://localhost:8088',
  });
}

export const env: Env = parseEnv({
  EXPO_PUBLIC_API_BASE_URL: process.env.EXPO_PUBLIC_API_BASE_URL,
});
