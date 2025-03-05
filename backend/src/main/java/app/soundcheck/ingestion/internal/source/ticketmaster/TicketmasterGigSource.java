package app.soundcheck.ingestion.internal.source.ticketmaster;

import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.source.CallBudget;
import app.soundcheck.ingestion.internal.source.GigSource;
import app.soundcheck.ingestion.internal.source.SourceIssue;
import app.soundcheck.ingestion.internal.source.SourcePage;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * UK music events from the Ticketmaster Discovery API.
 *
 * <p>The API refuses to page past ~1,000 results per query, so the horizon is cut into windows that each
 * stay under that cap: weeks first; a week with more than 1,000 events is re-queried as days; a day that
 * is still too big is re-queried per market. The work is a queue of pending requests, consumed lazily
 * as the caller pulls pages off the stream.
 */
@Component
@ConditionalOnProperty(name = "soundcheck.ingestion.mode", havingValue = "live")
public class TicketmasterGigSource implements GigSource {

    /** Ticketmaster's deep-paging cap: {@code size * page} must stay below this. */
    static final int PAGING_CAP = 1000;

    private static final Logger log = LoggerFactory.getLogger(TicketmasterGigSource.class);
    private static final DateTimeFormatter TM_INSTANT = DateTimeFormatter.ISO_INSTANT;

    private final TicketmasterClient client;
    private final int pageSize;
    private final List<String> marketIds;

    public TicketmasterGigSource(TicketmasterClient client, IngestionProperties properties) {
        this.client = client;
        this.pageSize = properties.ticketmaster().pageSize();
        this.marketIds = properties.ticketmaster().marketIds();
    }

    @Override
    public String name() {
        return TicketmasterMapper.SOURCE;
    }

    @Override
    public Stream<SourcePage> pages(Instant from, Instant to, CallBudget budget) {
        Iterator<SourcePage> pages = new PageIterator(from, to, budget);
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(pages, Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    enum Level {
        WEEK,
        DAY,
        MARKET
    }

    /** One pending request: a time window (optionally narrowed to a market) and a page number. */
    record Request(Instant from, Instant to, Level level, @Nullable String marketId, int page) {

        Request nextPage() {
            return new Request(from, to, level, marketId, page + 1);
        }
    }

    private final class PageIterator implements Iterator<SourcePage> {

        private final Deque<Request> pending = new ArrayDeque<>();
        private final CallBudget budget;
        /** For a day split by market: the day's total, and the sum of the market totals seen so far. */
        private final Map<Instant, int[]> marketSplits = new HashMap<>();

        private @Nullable SourcePage next;

        PageIterator(Instant from, Instant to, CallBudget budget) {
            this.budget = budget;
            split(from, to, Duration.ofDays(7), Level.WEEK).forEach(pending::addLast);
        }

        @Override
        public boolean hasNext() {
            while (next == null && !pending.isEmpty() && budget.allowsAnotherCall()) {
                next = fetch(pending.pollFirst());
            }
            return next != null;
        }

        @Override
        public SourcePage next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            SourcePage page = next;
            next = null;
            return page;
        }

        /** Runs one request; returns its page, or null when the window was split instead. */
        private @Nullable SourcePage fetch(Request request) {
            Map<String, String> query = query(request);
            JsonNode body = client.events(query, budget);
            TicketmasterMapper.Mapped mapped = TicketmasterMapper.map(body);
            List<SourceIssue> issues = new ArrayList<>(mapped.issues());

            if (request.page() == 0 && mapped.totalElements() > PAGING_CAP) {
                if (request.level() == Level.WEEK) {
                    log.info(
                            "Ticketmaster week from {} has {} events: splitting into days",
                            request.from(),
                            mapped.totalElements());
                    pushFront(split(request.from(), request.to(), Duration.ofDays(1), Level.DAY));
                    return null;
                }
                if (request.level() == Level.DAY) {
                    log.info(
                            "Ticketmaster day from {} has {} events: splitting into {} markets",
                            request.from(),
                            mapped.totalElements(),
                            marketIds.size());
                    marketSplits.put(request.from(), new int[] {mapped.totalElements(), 0, marketIds.size()});
                    List<Request> perMarket = new ArrayList<>();
                    for (String marketId : marketIds) {
                        perMarket.add(new Request(request.from(), request.to(), Level.MARKET, marketId, 0));
                    }
                    pushFront(perMarket);
                    return null;
                }
                issues.add(new SourceIssue(
                        "WINDOW_TRUNCATED",
                        null,
                        "market " + request.marketId() + " on " + request.from() + " has " + mapped.totalElements()
                                + " events; only the first " + PAGING_CAP + " can be paged"));
            }
            if (request.level() == Level.MARKET && request.page() == 0) {
                checkMarketCoverage(request, mapped.totalElements(), issues);
            }
            boolean morePages =
                    request.page() + 1 < mapped.totalPages() && (long) (request.page() + 1) * pageSize < PAGING_CAP;
            if (morePages) {
                pending.addFirst(request.nextPage());
            }
            return new SourcePage(TicketmasterClient.describe(query), body, mapped.gigs(), issues);
        }

        /** After the last market of a split day: do the markets add up to the day's total? */
        private void checkMarketCoverage(Request request, int marketTotal, List<SourceIssue> issues) {
            int[] split = marketSplits.get(request.from());
            if (split == null) {
                return;
            }
            split[1] += marketTotal;
            split[2]--;
            if (split[2] == 0) {
                marketSplits.remove(request.from());
                if (split[1] < split[0]) {
                    issues.add(new SourceIssue(
                            "MARKET_SPLIT_INCOMPLETE",
                            null,
                            "day " + request.from() + " has " + split[0] + " events but its markets total "
                                    + split[1]));
                }
            }
        }

        private void pushFront(List<Request> requests) {
            for (int i = requests.size() - 1; i >= 0; i--) {
                pending.addFirst(requests.get(i));
            }
        }
    }

    /** Cuts {@code [from, to)} into consecutive windows of at most {@code step}. */
    static List<Request> split(Instant from, Instant to, Duration step, Level level) {
        List<Request> windows = new ArrayList<>();
        for (Instant start = from; start.isBefore(to); start = start.plus(step)) {
            Instant end = start.plus(step);
            windows.add(new Request(start, end.isAfter(to) ? to : end, level, null, 0));
        }
        return windows;
    }

    /** The event search query. Windows are half-open, so the inclusive {@code endDateTime} is one second early. */
    private Map<String, String> query(Request request) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("countryCode", "GB");
        query.put("classificationName", "music");
        query.put("startDateTime", TM_INSTANT.format(request.from().truncatedTo(ChronoUnit.SECONDS)));
        query.put("endDateTime", TM_INSTANT.format(request.to().minusSeconds(1).truncatedTo(ChronoUnit.SECONDS)));
        query.put("size", String.valueOf(pageSize));
        query.put("sort", "date,asc");
        query.put("page", String.valueOf(request.page()));
        if (request.marketId() != null) {
            query.put("marketId", request.marketId());
        }
        return query;
    }
}
