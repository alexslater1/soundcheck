package app.soundcheck.feed;

import java.util.UUID;

/** Domain event: a ranking config became the active one. */
public record RankingConfigActivated(UUID configId, int version) {}
