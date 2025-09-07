package app.soundcheck.analytics.internal.web;

import app.soundcheck.analytics.internal.AdminAnalytics;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.Actors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console overview's API: KPIs and a daily series, For You engagement, and the top
 * artists, gigs and upload hours of a period [{@code from}, {@code to}) of at most 90 days. Synthetic
 * accounts' share of the activity always comes with the KPIs. Operators only.
 */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1/admin/analytics")
@PreAuthorize(Actors.ADMIN_ONLY)
@Validated
@Tag(name = "admin-analytics", description = "Console: activity, uploads and feed engagement (operators only)")
public class AdminAnalyticsController {

    private final AdminAnalytics analytics;

    public AdminAnalyticsController(AdminAnalytics analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/overview")
    @Operation(
            operationId = "adminAnalyticsOverview",
            summary = "KPIs against the previous period, a series per UK day, and the share of the activity that"
                    + " synthetic accounts produced. 400 ANALYTICS_RANGE_INVALID unless from < to ≤ from + 90 days")
    public AdminAnalytics.Overview overview(@RequestParam Instant from, @RequestParam Instant to) {
        return analytics.overview(from, to);
    }

    @GetMapping("/feed")
    @Operation(
            operationId = "adminAnalyticsFeed",
            summary = "For You engagement: by position, config version, exploration, watch fraction, clip length"
                    + " and photo vs video")
    public AdminAnalytics.FeedEngagement feed(@RequestParam Instant from, @RequestParam Instant to) {
        return analytics.feed(from, to);
    }

    @GetMapping("/content")
    @Operation(
            operationId = "adminAnalyticsContent",
            summary = "The top 10 artists and gigs by positive engagements, then posts, and uploads by UK hour")
    public AdminAnalytics.ContentStats content(@RequestParam Instant from, @RequestParam Instant to) {
        return analytics.content(from, to);
    }
}
