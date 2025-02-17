/**
 * The generated client (orval 7.5) adds a query string only when `URLSearchParams.size` is non-zero.
 * React Native's `URLSearchParams` (and the one Expo SDK 52 installs) has no `size`, which would drop
 * every query string, so it is defined here where it is missing. Browsers and Node already have it.
 */
if (!('size' in URLSearchParams.prototype)) {
  Object.defineProperty(URLSearchParams.prototype, 'size', {
    configurable: true,
    get(this: URLSearchParams) {
      return Array.from(this as unknown as Iterable<[string, string]>).length;
    },
  });
}

export {};
