package app.soundcheck.social.internal.web;

import app.soundcheck.social.NotifyLevel;
import jakarta.validation.constraints.NotNull;

/** The body of {@code PUT /artists/{id}/follow/notify} and {@code PUT /venues/{id}/follow/notify}. */
public record NotifyRequest(@NotNull NotifyLevel level) {}
