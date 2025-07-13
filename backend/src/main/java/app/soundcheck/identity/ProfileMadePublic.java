package app.soundcheck.identity;

import java.util.UUID;

/**
 * Domain event: a private profile was switched to public. Social accepts the follow requests
 * still waiting for it (a public profile has no gate). Published only on a real change.
 */
public record ProfileMadePublic(UUID userId) {}
