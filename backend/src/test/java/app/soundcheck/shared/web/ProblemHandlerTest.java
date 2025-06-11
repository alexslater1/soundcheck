package app.soundcheck.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.shared.config.JacksonConfig;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The Problem Details contract, exercised through a test-only controller. */
@WebMvcTest(controllers = ProblemHandlerTest.ThrowingController.class)
@Import({ProblemHandler.class, ProblemHandlerTest.ThrowingController.class, RateLimiter.class, JacksonConfig.class})
@WithMockUser
class ProblemHandlerTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void apiExceptionBecomesProblemDetailWithTypeAndCode() {
        assertThat(mvc.get().uri("/test/conflict"))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("type").isEqualTo("https://soundcheck.app/problems/handle-taken");
                    assertThat(json).extractingPath("code").isEqualTo("HANDLE_TAKEN");
                    assertThat(json).extractingPath("status").isEqualTo(409);
                    assertThat(json).extractingPath("detail").isEqualTo("Handle already taken");
                    assertThat(json).extractingPath("title").isEqualTo("Conflict");
                });
    }

    @Test
    void validationFailureListsFieldErrors() {
        assertThat(
                        mvc.post()
                                .uri("/test/validate")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"\"}")
                                .with(org.springframework.security.test.web.servlet.request
                                        .SecurityMockMvcRequestPostProcessors.csrf()))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("code").isEqualTo("VALIDATION_FAILED");
                    assertThat(json).extractingPath("errors[0].field").isEqualTo("name");
                    assertThat(json)
                            .extractingPath("errors[0].message")
                            .asString()
                            .isNotBlank();
                });
    }

    @Test
    void unexpectedExceptionIsA500WithoutLeakingDetails() {
        assertThat(mvc.get().uri("/test/boom"))
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("code").isEqualTo("INTERNAL_ERROR");
                    assertThat(json).extractingPath("detail").asString().doesNotContain("secret");
                });
    }

    @Test
    void frameworkErrorsGetACodeToo() {
        assertThat(
                        mvc.post()
                                .uri("/test/validate")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("not json")
                                .with(org.springframework.security.test.web.servlet.request
                                        .SecurityMockMvcRequestPostProcessors.csrf()))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .satisfies(json -> assertThat(json).extractingPath("code").isEqualTo("BAD_REQUEST"));
    }

    @Test
    void problemsQuoteTheCurrentTraceIdAndOnlyThat() {
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        SpanContext context =
                SpanContext.create(traceId, "b7ad6b7169203331", TraceFlags.getSampled(), TraceState.getDefault());
        try (Scope ignored = Span.wrap(context).makeCurrent()) {
            // a controller's exception, and the static builder the filter chain uses (401, 403, auth 429)
            assertThat(mvc.get().uri("/test/boom")).bodyJson().satisfies(json -> assertThat(json)
                    .extractingPath("traceId")
                    .isEqualTo(traceId));
            assertThat(ProblemHandler.problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required")
                            .getProperties())
                    .containsEntry("traceId", traceId);
        }
        assertThat(ProblemHandler.problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required")
                        .getProperties())
                .doesNotContainKey("traceId");
    }

    @Test
    void slugsAreKebabCase() {
        assertThat(ProblemHandler.slug("FEED_SESSION_EXPIRED")).isEqualTo("feed-session-expired");
    }

    @RestController
    static class ThrowingController {

        record Payload(@NotBlank String name) {}

        @GetMapping("/test/conflict")
        String conflict() {
            throw ApiException.conflict("HANDLE_TAKEN", "Handle already taken");
        }

        @GetMapping("/test/boom")
        String boom() {
            throw new IllegalStateException("secret internal state");
        }

        @PostMapping("/test/validate")
        String validate(@Valid @RequestBody Payload payload) {
            return payload.name();
        }
    }
}
