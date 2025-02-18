/**
 * Users, credentials, tokens, roles, explicit genre preferences, account deletion (schema {@code identity}).
 * Reads catalog's reference data (genres, cities) for onboarding and the {@code Me} view.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared", "catalog"})
package app.soundcheck.identity;
