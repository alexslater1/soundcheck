package app.soundcheck.catalog;

import org.jspecify.annotations.Nullable;

/** A UK town or city with its centroid ({@code catalog.city}); the only location users ever pick. */
public record City(
        int id, String name, @Nullable String region, double lat, double lng, @Nullable Integer population) {}
