package app.soundcheck.catalog;

import org.jspecify.annotations.Nullable;

/** A canonical genre ({@code catalog.genre}); {@code parentId} groups sub-genres under a parent. */
public record Genre(short id, String slug, String name, @Nullable Short parentId) {}
