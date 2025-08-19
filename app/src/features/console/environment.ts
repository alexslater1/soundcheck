/**
 * The top bar's environment badge: which stack the console is changing. The app knows only its
 * API base URL, so a local host means the laptop stack and anything else the AWS one.
 */
export function environmentLabel(apiBaseUrl: string): 'LOCAL' | 'AWS' {
  let host: string;
  try {
    host = new URL(apiBaseUrl).hostname;
  } catch {
    return 'LOCAL';
  }
  return host === 'localhost' ||
    host === '127.0.0.1' ||
    host.endsWith('.local') ||
    /^10\.|^192\.168\./.test(host)
    ? 'LOCAL'
    : 'AWS';
}
