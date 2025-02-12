/**
 * Ranking configs, candidate generation, features, scorer, diversity, sessions, served-item log,
 * explanations, replay evaluation (schema {@code feed}).
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared", "identity", "social", "catalog", "content", "signals"})
package app.soundcheck.feed;
