/**
 * A random (version 4) UUID for idempotency keys and local ids. Uses the platform's generator where
 * there is one (browsers); otherwise builds one from `Math.random`, which is enough here: the key has
 * to be unique among one user's uploads, not unguessable.
 */
export function newUuid(): string {
  const platform = globalThis.crypto as { randomUUID?: () => string } | undefined;
  if (platform?.randomUUID) {
    return platform.randomUUID();
  }
  const hex = (count: number) =>
    Array.from({ length: count }, () => Math.floor(Math.random() * 16).toString(16)).join('');
  // Version nibble 4; variant bits 10xx (8, 9, a or b).
  const variant = (8 + Math.floor(Math.random() * 4)).toString(16);
  return `${hex(8)}-${hex(4)}-4${hex(3)}-${variant}${hex(3)}-${hex(12)}`;
}
