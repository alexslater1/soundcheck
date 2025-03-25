package app.soundcheck.shared.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The current trace as a W3C {@code traceparent} string ({@code 00-<trace id>-<span id>-<flags>}), for
 * handing a trace across something that is not an HTTP call: a stored event, a queue message. The outbox
 * makes this necessary: an event may be delivered minutes later, by another thread or after a restart,
 * when no thread-local trace context exists any more, so the id has to travel inside the event.
 */
@Component
public class Traceparent {

    private final ObjectProvider<Tracer> tracer;

    public Traceparent(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    /** Empty when tracing is off or nothing is being traced on this thread. */
    public Optional<String> current() {
        Tracer current = tracer.getIfAvailable();
        Span span = current == null ? null : current.currentSpan();
        if (span == null || span.isNoop()) {
            return Optional.empty();
        }
        return Optional.of(format(span.context()));
    }

    static String format(TraceContext context) {
        String flags = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
        return "00-" + context.traceId() + "-" + context.spanId() + "-" + flags;
    }
}
