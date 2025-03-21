import { Linking } from 'react-native';

/** True for URLs the app is willing to hand to the system: plain web links only. */
export function isWebUrl(url: string | null | undefined): url is string {
  return typeof url === 'string' && /^https?:\/\//i.test(url.trim());
}

/**
 * Opens a link from API data in the browser. Anything that is not http(s) is ignored: a ticket URL comes
 * from a third-party feed and must not be able to launch `javascript:`, `intent:` or another app's scheme.
 */
export async function openWebLink(url: string | null | undefined): Promise<boolean> {
  if (!isWebUrl(url)) return false;
  await Linking.openURL(url.trim());
  return true;
}
