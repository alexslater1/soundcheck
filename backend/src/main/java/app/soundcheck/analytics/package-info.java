/**
 * Read-only console queries across modules via SQL views (schema {@code analytics}). The documented
 * exception to strict module reads: it reads other schemas through views, never their code.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared"})
package app.soundcheck.analytics;
