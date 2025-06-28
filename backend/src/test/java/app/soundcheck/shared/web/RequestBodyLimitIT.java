package app.soundcheck.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * Request bodies over {@code soundcheck.http.max-request-body} (1 MB, the
 * default this context runs with) are refused with {@code 413 REQUEST_TOO_LARGE}, whether the client
 * declares the length or streams the body chunked, and a body just under the limit is served normally.
 * The JDK client, because TestRestTemplate's Apache client buffers and retries.
 */
class RequestBodyLimitIT extends IntegrationTest {

    private static final HttpClient HTTP =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final int LIMIT = 1024 * 1024;

    @Autowired
    TestRestTemplate rest;

    @Test
    void aDeclaredBodyOverTheLimitIsRefusedBeforeItIsRead() throws Exception {
        HttpResponse<String> response = login(HttpRequest.BodyPublishers.ofByteArray(loginBody(LIMIT + 1)));
        assertTooLarge(response);
    }

    @Test
    void aChunkedBodyOverTheLimitIsRefusedWhileItIsRead() throws Exception {
        byte[] body = loginBody(LIMIT + 1);
        // No Content-Length: the JDK client sends it chunked, so only counting while reading can stop it.
        HttpResponse<String> response =
                login(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)));
        assertTooLarge(response);
    }

    @Test
    void aBodyJustUnderTheLimitIsServedNormally() throws Exception {
        HttpResponse<String> response = login(HttpRequest.BodyPublishers.ofByteArray(loginBody(LIMIT - 64)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"INVALID_CREDENTIALS\"");
    }

    private static void assertTooLarge(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.body())
                .contains("\"code\":\"REQUEST_TOO_LARGE\"")
                .contains("https://soundcheck.app/problems/request-too-large");
    }

    /** A login for an unknown email, padded with an ignored field to exactly {@code size} bytes. */
    private static byte[] loginBody(int size) {
        String head = "{\"email\":\"nobody-" + UUID.randomUUID() + "@example.com\",\"password\":\"not the password\","
                + "\"padding\":\"";
        String tail = "\"}";
        String json = head + "x".repeat(size - head.length() - tail.length()) + tail;
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        assertThat(bytes).hasSize(size);
        return bytes;
    }

    private HttpResponse<String> login(HttpRequest.BodyPublisher body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(rest.getRootUri() + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-Client", "android")
                .POST(body)
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
