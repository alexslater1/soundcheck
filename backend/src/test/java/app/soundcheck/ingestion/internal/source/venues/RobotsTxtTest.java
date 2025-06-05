package app.soundcheck.ingestion.internal.source.venues;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** RFC 9309 rules as the venue fetcher applies them, on shapes seen on the researched venue sites. */
class RobotsTxtTest {

    @Test
    void squarespaceSitesAllowThePageButNotTheirJsonAndIcalExports() {
        RobotsTxt robots = RobotsTxt.parse(
                """
                User-agent: *
                Disallow: /config
                Disallow: /*?format=json
                Disallow: /*?format=ical
                Disallow: /*?format=page-context
                """,
                "SoundcheckBot");
        assertThat(robots.allows("/events")).isTrue();
        assertThat(robots.allows("/events?format=json")).isFalse();
        assertThat(robots.allows("/events?format=ical")).isFalse();
        assertThat(robots.allows("/config/settings")).isFalse();
    }

    @Test
    void theLongestRuleWinsAndAllowWinsATie() {
        RobotsTxt robots = RobotsTxt.parse(
                """
                User-agent: *
                Allow: /
                Disallow: /__ical/*
                Disallow: /receipts/
                Allow: /receipts/public
                """,
                "SoundcheckBot");
        assertThat(robots.allows("/")).isTrue();
        assertThat(robots.allows("/events/2026-10-02-pale-kites")).isTrue();
        assertThat(robots.allows("/__ical/events.ics")).isFalse();
        assertThat(robots.allows("/receipts/123")).isFalse();
        assertThat(robots.allows("/receipts/public/1")).isTrue();
    }

    @Test
    void aGroupNamingUsWinsOverTheStarGroupAndBlockedAiCrawlersDoNotConcernUs() {
        RobotsTxt robots = RobotsTxt.parse(
                """
                User-agent: GPTBot
                User-agent: ClaudeBot
                Disallow: /

                User-agent: *
                Allow: /api/
                Crawl-delay: 2

                User-agent: SoundcheckBot
                Disallow: /private
                Crawl-delay: 5
                """,
                "SoundcheckBot/0.1");
        assertThat(robots.allows("/api/v1/events")).isTrue();
        assertThat(robots.allows("/private/x")).isFalse();
        assertThat(robots.crawlDelay()).isEqualTo(Duration.ofSeconds(5));

        RobotsTxt starOnly =
                RobotsTxt.parse("User-agent: GPTBot\nDisallow: /\n\nUser-agent: *\nCrawl-delay: 2\n", "SoundcheckBot");
        assertThat(starOnly.allows("/rss")).isTrue();
        assertThat(starOnly.crawlDelay()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void aSiteThatDisallowsEverythingIsLeftAlone() {
        RobotsTxt robots = RobotsTxt.parse("User-agent: *\nDisallow: /\n", "SoundcheckBot");
        assertThat(robots.allows("/events")).isFalse();
    }

    @Test
    void anEmptyDisallowAndCommentsAllowEverythingAndAnchorsWork() {
        RobotsTxt robots =
                RobotsTxt.parse("# hi\nUser-agent: * # all\nDisallow:\nDisallow: /*.pdf$\n", "SoundcheckBot");
        assertThat(robots.allows("/events")).isTrue();
        assertThat(robots.allows("/menu.pdf")).isFalse();
        assertThat(robots.allows("/menu.pdf?x=1")).isTrue();
        assertThat(RobotsTxt.allowAll().allows("/anything")).isTrue();
    }
}
