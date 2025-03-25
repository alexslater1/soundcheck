package app.soundcheck.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class TraceparentTest {

    @SuppressWarnings("unchecked")
    private static Traceparent with(Tracer tracer) {
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tracer);
        return new Traceparent(provider);
    }

    @Test
    void formatsTheCurrentSpanAsW3cTraceparent() {
        TraceContext context = mock(TraceContext.class);
        when(context.traceId()).thenReturn("4bf92f3577b34da6a3ce929d0e0e4736");
        when(context.spanId()).thenReturn("00f067aa0ba902b7");
        when(context.sampled()).thenReturn(true);
        Span span = mock(Span.class);
        when(span.context()).thenReturn(context);
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(span);

        assertThat(with(tracer).current()).contains("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        when(context.sampled()).thenReturn(false);
        assertThat(with(tracer).current())
                .hasValueSatisfying(value -> assertThat(value).endsWith("-00"));
    }

    @Test
    void isEmptyWithoutATracerOrASpan() {
        assertThat(with(null).current()).isEmpty();
        Tracer idle = mock(Tracer.class);
        when(idle.currentSpan()).thenReturn(null);
        assertThat(with(idle).current()).isEmpty();
        Span noop = mock(Span.class);
        when(noop.isNoop()).thenReturn(true);
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(noop);
        assertThat(with(tracer).current()).isEmpty();
    }
}
