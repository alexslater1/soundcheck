/**
 * The web has no push (Android through FCM only); these do nothing there. The phone's version
 * is `pushRegistration.native.ts`.
 */
export async function registerForPush(): Promise<string | null> {
  return null;
}

export async function unregisterFromPush(): Promise<void> {}

export async function sendToken(token: string): Promise<string> {
  return token;
}
