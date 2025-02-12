/** Posts, artist tags, likes, comments, reports, moderation state (schema {@code content}). */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared", "identity", "catalog", "media", "social"})
package app.soundcheck.content;
