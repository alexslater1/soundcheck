import {
  Inter_400Regular,
  Inter_500Medium,
  Inter_600SemiBold,
  Inter_700Bold,
} from '@expo-google-fonts/inter';
import {
  SpaceGrotesk_500Medium,
  SpaceGrotesk_600SemiBold,
  SpaceGrotesk_700Bold,
} from '@expo-google-fonts/space-grotesk';

import type { typeScale } from './tokens';

/**
 * Typefaces. The phone's default font was the plainest part of the app. "grotesk" sets headings and
 * labels in Space Grotesk (a geometric face with character, as on gig posters) and running text in
 * Inter (made for screens); "system" keeps the platform font. Switchable in Settings → Appearance.
 * Both faces are under the SIL Open Font License.
 */
export type TypeStyle = 'grotesk' | 'system';

/** Every font file the app loads (expo-font `useFonts`), by the family name it registers. */
export const FONT_FILES = {
  Inter_400Regular,
  Inter_500Medium,
  Inter_600SemiBold,
  Inter_700Bold,
  SpaceGrotesk_500Medium,
  SpaceGrotesk_600SemiBold,
  SpaceGrotesk_700Bold,
};

type Family = keyof typeof FONT_FILES;
type Variant = keyof typeof typeScale;

/** Headings and labels in the display face; the rest in the text face. */
const DISPLAY: readonly Variant[] = ['display', 'title', 'label'];

/**
 * The family for a variant at a weight, or null for the platform font. Each weight is its own file
 * and family name, so the weight is chosen here and not left to `fontWeight` (Android would fake a
 * bold on top of the regular file).
 */
export function fontFamily(
  style: TypeStyle,
  variant: Variant,
  weight: string | number | undefined,
): Family | null {
  if (style === 'system') {
    return null;
  }
  const w =
    typeof weight === 'number' ? weight : weight === 'bold' ? 700 : Number(weight ?? 400) || 400;
  if (DISPLAY.includes(variant)) {
    return w >= 700
      ? 'SpaceGrotesk_700Bold'
      : w >= 600
        ? 'SpaceGrotesk_600SemiBold'
        : 'SpaceGrotesk_500Medium';
  }
  return w >= 700
    ? 'Inter_700Bold'
    : w >= 600
      ? 'Inter_600SemiBold'
      : w >= 500
        ? 'Inter_500Medium'
        : 'Inter_400Regular';
}
