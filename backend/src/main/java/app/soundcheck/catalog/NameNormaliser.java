package app.soundcheck.catalog;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The one definition of {@code name_norm} for artists and venues: lower-case, accents
 * removed, {@code &} and {@code +} read as "and", punctuation stripped, whitespace collapsed, a leading
 * "the " dropped. Ingestion uses it to match entities across sources; search uses it on the query, so
 * both sides of every comparison are normalised the same way.
 *
 * <pre>
 *   "The 1975"               → "1975"
 *   "Florence + The Machine" → "florence and the machine"
 *   "Sigur Rós"              → "sigur ros"
 *   "AC/DC"                  → "ac dc"
 *   "Guns N' Roses"          → "guns n roses"
 * </pre>
 */
public final class NameNormaliser {

    private NameNormaliser() {}

    public static String normalise(String name) {
        String lower = name.strip().toLowerCase(Locale.ROOT);
        String text = unaccent(lower).replace("&", " and ").replace("+", " and ");
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            i += Character.charCount(c);
            if (Character.isLetterOrDigit(c)) {
                out.appendCodePoint(c);
            } else if (c == '.' || c == '\'' || c == '’') {
                // Dots and apostrophes vanish: "D.J." = "DJ", "Guns N' Roses" = "Guns N Roses".
            } else {
                out.append(' '); // other punctuation separates words: "AC/DC" → "ac dc", "Jay-Z" → "jay z"
            }
        }
        String collapsed = out.toString().strip().replaceAll("\\s+", " ");
        if (collapsed.startsWith("the ") && collapsed.length() > 4) {
            collapsed = collapsed.substring(4);
        }
        // A name made only of punctuation ("!!!") must not normalise to nothing.
        return collapsed.isEmpty() ? lower : collapsed;
    }

    /** Strips diacritics (NFD + remove combining marks) and maps the letters NFD leaves alone. */
    static String unaccent(String lower) {
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            switch (c) {
                case 'ø' -> out.append('o');
                case 'đ', 'ð' -> out.append('d');
                case 'ł' -> out.append('l');
                case 'ß' -> out.append("ss");
                case 'æ' -> out.append("ae");
                case 'œ' -> out.append("oe");
                case 'þ' -> out.append("th");
                case 'ı' -> out.append('i');
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
