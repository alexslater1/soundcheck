package app.soundcheck.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * A log event goes through the Logback {@code OTEL} appender ({@link OpenTelemetryLogAppender})
 * into the application's OpenTelemetry and out of an exporter, with the trace and span it happened in.
 * Here the exporter is an in-memory one; locally it is OTLP to Loki (verified by hand).
 */
@Import(LogExportIT.CapturingExporterConfig.class)
class LogExportIT extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger("app.soundcheck.logexport");

    @Autowired
    CapturingExporter exported;

    @Test
    void aLogEventIsExportedWithItsTraceAndSpan() throws Exception {
        String marker = "log-export-" + UUID.randomUUID();
        SpanContext context = SpanContext.create(
                "0af7651916cd43dd8448eb211c80319c",
                "b7ad6b7169203331",
                TraceFlags.getSampled(),
                TraceState.getDefault());
        try (Scope ignored = Span.wrap(context).makeCurrent()) {
            log.warn("{} happened", marker);
        }

        LogRecordData record = awaitRecord(marker);
        assertThat(record.getSeverity()).isEqualTo(Severity.WARN);
        assertThat(record.getSpanContext().getTraceId()).isEqualTo(context.getTraceId());
        assertThat(record.getSpanContext().getSpanId()).isEqualTo(context.getSpanId());
        assertThat(record.getInstrumentationScopeInfo().getName()).isEqualTo("app.soundcheck.logexport");
        assertThat(record.getResource().getAttribute(AttributeKey.stringKey("service.name")))
                .isEqualTo("soundcheck");
    }

    /** The batch processor exports every second (Boot's default schedule delay); allow ten. */
    private LogRecordData awaitRecord(String marker) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Optional<LogRecordData> found = exported.records.stream()
                    .filter(r -> r.getBodyValue() != null
                            && r.getBodyValue().asString().contains(marker))
                    .findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no exported log record contains " + marker);
    }

    static final class CapturingExporter implements LogRecordExporter {

        final List<LogRecordData> records = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<LogRecordData> logs) {
            records.addAll(logs);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CapturingExporterConfig {

        @Bean
        CapturingExporter capturingExporter() {
            return new CapturingExporter();
        }
    }
}
