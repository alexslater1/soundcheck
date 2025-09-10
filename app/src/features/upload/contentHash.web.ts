/** Above this, the browser does not hash: the whole file would have to be read into memory at once. */
export const MAX_HASHED_BYTES = 200 * 1024 * 1024;

/**
 * Browser: the SHA-256 of the picked file, with `crypto.subtle` (only on https or localhost).
 * The prefix keeps it apart from the phone's MD5, so the same clip posted from the phone is not
 * recognised on the web; that is accepted. Null for a file over 200 MB, or when the browser cannot
 * hash: the upload goes ahead without the guard.
 */
export async function contentHash(uri: string): Promise<string | null> {
  try {
    const subtle = globalThis.crypto?.subtle;
    if (!subtle) {
      return null;
    }
    const blob = await (await fetch(uri)).blob();
    if (blob.size > MAX_HASHED_BYTES) {
      return null;
    }
    const digest = new Uint8Array(await subtle.digest('SHA-256', await blob.arrayBuffer()));
    return `sha256:${Array.from(digest, (byte) => byte.toString(16).padStart(2, '0')).join('')}`;
  } catch {
    return null;
  }
}
