/**
 * Interaction event intake, partition maintenance, post stats, hourly rollups, affinities (schema
 * {@code signals}). Depends on content for facts about posts, and on identity only for the account
 * purge step ({@code AccountPurgeStep}): signals consumes identity's deletion event, which
 * needs the same dependency.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared", "content", "identity"})
package app.soundcheck.signals;
