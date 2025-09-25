import { Platform } from 'react-native';
import { z } from 'zod';

/**
 * Public runtime configuration. `EXPO_PUBLIC_*` variables are inlined at build time by Expo.
 * Every request — including media URLs returned by the API — is absolute against `apiBaseUrl`.
 * Dev: http://localhost:8088 (the Caddy edge; Android uses `adb reverse tcp:8088 tcp:8088`).
 * AWS: the web build is made with an empty value (`make web EXPO_PUBLIC_API_BASE_URL=`) and then
 * talks to whichever origin served it, so one build works on any CloudFront domain; native builds still
 * need an absolute URL (`make apk TARGET=aws` passes the CloudFront address).
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

/** Where the code runs: the platform, and on the web the page's origin (`window.location.origin`). */
export type Runtime = { os: string; origin: string | undefined };

function currentRuntime(): Runtime {
  return { os: Platform.OS, origin: globalThis.location?.origin };
}

export function parseEnv(
  source: Record<string, string | undefined>,
  runtime: Runtime = currentRuntime(),
): Env {
  const configured = source.EXPO_PUBLIC_API_BASE_URL ?? 'http://localhost:8088';
  if (configured === '') {
    if (runtime.os !== 'web' || !runtime.origin) {
      throw new Error(
        'EXPO_PUBLIC_API_BASE_URL is empty: only a web build may use the origin that served it; ' +
          'a native build needs an absolute URL (make apk TARGET=aws)',
      );
    }
    return envSchema.parse({ apiBaseUrl: runtime.origin });
  }
  return envSchema.parse({ apiBaseUrl: configured });
}

export const env: Env = parseEnv({
  EXPO_PUBLIC_API_BASE_URL: process.env.EXPO_PUBLIC_API_BASE_URL,
});
