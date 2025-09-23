package app.soundcheck.notifications.internal.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Firebase Cloud Messaging's HTTP v1 API. Google wants an OAuth access token: the service
 * account signs a short JWT (RS256, its own key) and trades it at the token endpoint for one that lasts
 * an hour, kept here until a minute before it runs out. Each message goes to one registration token.
 * 404, or a 400 that blames the token, means the device is gone; 429 and 5xx are retried.
 */
@Component
@ConditionalOnProperty(name = "soundcheck.push.enabled", havingValue = "true")
class FcmClient implements PushGateway {

    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final RestClient rest;
    private final String baseUrl;
    private final FcmCredentials credentials;
    private final String tokenUri;
    private final JsonMapper json;
    private final Clock clock;

    private @Nullable String accessToken;
    private Instant accessTokenExpires = Instant.EPOCH;

    @Autowired
    FcmClient(PushProperties properties, JsonMapper json, Clock clock) {
        this(properties, FcmCredentials.from(properties, json), json, clock);
    }

    /** Tests: a key made on the spot instead of a file. */
    FcmClient(PushProperties properties, FcmCredentials credentials, JsonMapper json, Clock clock) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // as in PostcodesIoClient: no h2c upgrade on a POST
                .connectTimeout(Duration.ofSeconds(5))
                .build());
        requests.setReadTimeout(Duration.ofSeconds(10));
        this.rest = RestClient.builder().requestFactory(requests).build();
        this.credentials = credentials;
        this.tokenUri = properties.oauthUrl().isBlank() ? credentials.tokenUri() : properties.oauthUrl();
        this.json = json;
        this.clock = clock;
        this.baseUrl = properties.fcmBaseUrl();
    }

    @Override
    @Retryable(interceptor = "pushCalls")
    public Outcome send(String token, PushMessage message) {
        Map<String, Object> body = Map.of(
                "message",
                Map.of(
                        "token", token,
                        "notification", Map.of("title", message.title(), "body", message.body()),
                        "data", message.data(),
                        // The app's Android channel (pushRegistration.native.ts); HIGH wakes a sleeping phone.
                        "android", Map.of("priority", "HIGH", "notification", Map.of("channel_id", "activity"))));
        String payload;
        try {
            payload = json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        try {
            return rest.post()
                    .uri(baseUrl + "/v1/projects/" + credentials.projectId() + "/messages:send")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        String text = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
                        return outcome(status, text);
                    });
        } catch (ResourceAccessException e) {
            throw new TransientPushException("FCM unreachable", e);
        }
    }

    private Outcome outcome(int status, String body) {
        if (status / 100 == 2) {
            return Outcome.SENT;
        }
        if (status == 404 || (status == 400 && blamesToken(body))) {
            return Outcome.TOKEN_GONE;
        }
        if (status == 401) {
            forgetAccessToken(); // expired early or revoked: the retry fetches a new one
            throw new TransientPushException("FCM refused the access token");
        }
        if (status == 429 || status / 100 == 5) {
            throw new TransientPushException("FCM answered " + status);
        }
        throw new IllegalStateException("FCM answered " + status);
    }

    /** FCM's errorCode UNREGISTERED, or INVALID_ARGUMENT about the registration token. */
    private boolean blamesToken(String body) {
        JsonNode error;
        try {
            error = json.readTree(body.isBlank() ? "{}" : body).path("error");
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        for (JsonNode detail : error.path("details")) {
            if ("UNREGISTERED".equals(detail.path("errorCode").asText(""))) {
                return true;
            }
        }
        return error.path("message").asText("").toLowerCase().contains("registration token");
    }

    private synchronized String accessToken() {
        Instant now = clock.instant();
        if (accessToken == null || now.isAfter(accessTokenExpires.minusSeconds(60))) {
            fetchAccessToken(now);
        }
        return accessToken;
    }

    /** Also for tests: each starts with no cached token. */
    synchronized void forgetAccessToken() {
        accessToken = null;
    }

    private void fetchAccessToken(Instant now) {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.add("assertion", assertion(now));
        JsonNode answer;
        try {
            answer = rest.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        String text = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
                        if (status == 429 || status / 100 == 5) {
                            throw new TransientPushException("token endpoint answered " + status);
                        }
                        if (status / 100 != 2) {
                            // The text is Google's error (e.g. invalid_grant), never the assertion.
                            throw new IllegalStateException("token endpoint answered " + status + ": " + text);
                        }
                        return json.readTree(text);
                    });
        } catch (ResourceAccessException e) {
            throw new TransientPushException("token endpoint unreachable", e);
        }
        accessToken = answer.path("access_token").asText("");
        accessTokenExpires = now.plusSeconds(answer.path("expires_in").asLong(3600));
    }

    /** The service account's signed request for an access token (Google's OAuth 2.0 JWT bearer flow). */
    private String assertion(Instant now) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(credentials.clientEmail())
                .audience(tokenUri)
                .claim("scope", SCOPE)
                .claim("iat", now.getEpochSecond()) // as JwtIssuer: java.time only (ArchitectureTests)
                .claim("exp", now.plusSeconds(3600).getEpochSecond())
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(credentials.keyId())
                        .build(),
                claims);
        try {
            jwt.sign(new RSASSASigner(credentials.privateKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign the FCM token request", e);
        }
        return jwt.serialize();
    }
}
