package app.soundcheck.identity;

import java.util.UUID;

/**
 * A user whose handle or display name matches a search, with how well: {@code tier} 0 is
 * the exact handle or display name, 1 a handle that starts with the text, 2 a display name with a word
 * that starts with it, 3 any other match (the text inside a name, or a similar one). {@code similarity} (0–1) orders matches within a tier. Who the viewer may see and
 * whom they follow is for the caller (social) to decide.
 */
public record UserMatch(UUID id, int tier, double similarity) {}
