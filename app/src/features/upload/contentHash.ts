import { getInfoAsync } from 'expo-file-system';

/**
 * Android: the MD5 of the file as the user picked it, computed natively by expo-file-system
 * (`getInfoAsync(uri, { md5: true })` streams the file through the digest; the bytes never enter
 * JavaScript, and never whole into memory, which the `md5` property of expo-file-system/next's `File`
 * does). The server uses it only to say "You've posted this clip already"; it is not a security
 * control. Null when the file cannot be read: the upload goes ahead without the guard.
 *
 * It waits for the next tick first: the "Preparing" state is on screen before the hash is read.
 */
export async function contentHash(uri: string): Promise<string | null> {
  await new Promise((resolve) => setTimeout(resolve, 0));
  try {
    const info = await getInfoAsync(uri, { md5: true });
    const md5 = info.exists ? info.md5 : undefined;
    return md5 && /^[0-9a-f]{32}$/i.test(md5) ? `md5:${md5.toLowerCase()}` : null;
  } catch {
    return null;
  }
}
