package app.soundcheck.feed;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One line of "Why am I seeing this?": the kind, for the client to style, and the sentence
 * to show.
 */
public record Reason(
        @Schema(
                        allowableValues = {
                            "FOLLOWS_AUTHOR",
                            "FOLLOWS_ARTIST",
                            "WENT_TO_GIG",
                            "GOING_TO_GIG",
                            "GENRE_MATCH",
                            "NEARBY",
                            "TRENDING",
                            "FRIENDS_ENGAGED",
                            "NEW_FOR_YOU"
                        })
                String kind,
        String text) {}
