package app.soundcheck.shared.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Hand-made spans for work that does not start with an HTTP request: a queue consumer continuing the
 * trace whose id arrived in a message ({@link #continued}), and the stages inside it ({@link #child}).
 * Everything is a no-op when tracing is off, so callers need no null checks.
 */
@Component
public class Spans {

    /** An open span; closing it ends the span and restores the previous one as current. */
    public interface Scope extends AutoCloseable {
        Scope tag(String key, String value);

        void error(Throwable error);

        @Override
        void close();
    }

    private static final Scope NOOP = new Scope() {
        @Override
        public Scope tag(String key, String value) {
            return this;
        }

        @Override
        public void error(Throwable error) {}

        @Override
        public void close() {}
    };

    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;

    public Spans(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /**
     * Starts a span whose parent is the remote span named by a W3C {@code traceparent} string; a new
     * trace when the string is null or unreadable.
     */
    public Scope continued(@Nullable String traceparent, String name) {
        Tracer current = tracer.getIfAvailable();
        Propagator extractor = propagator.getIfAvailable();
        if (current == null) {
            return NOOP;
        }
        Span span = traceparent == null || extractor == null
                ? current.spanBuilder().setNoParent().name(name).start()
                : extractor
                        .extract(Map.of("traceparent", traceparent), Map::get)
                        .name(name)
                        .start();
        return open(current, span);
    }

    /** Starts a span under whatever span is current on this thread. */
    public Scope child(String name) {
        Tracer current = tracer.getIfAvailable();
        if (current == null) {
            return NOOP;
        }
        return open(current, current.nextSpan().name(name).start());
    }

    private static Scope open(Tracer tracer, Span span) {
        Tracer.SpanInScope inScope = tracer.withSpan(span);
        return new Scope() {
            @Override
            public Scope tag(String key, String value) {
                span.tag(key, value);
                return this;
            }

            @Override
            public void error(Throwable error) {
                span.error(error);
            }

            @Override
            public void close() {
                inScope.close();
                span.end();
            }
        };
    }
}
