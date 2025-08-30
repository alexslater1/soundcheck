package app.soundcheck.notifications.internal.push;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.soundcheck.notifications.internal.push.PushGateway.Outcome;
import app.soundcheck.shared.config.JsonMappers;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.retry.annotation.EnableRetry;

/**
 * The FCM client against a fake Google (WireMock): the OAuth exchange with a signed assertion, the message's
 * shape, the cached access token, gone tokens and retries. The service account key is made on the spot.
 */
@SpringBootTest(classes = FcmClientTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FcmClientTest {

    @RegisterExtension
    static WireMockExtension google = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    static final KeyPair KEYS = keys();
    static final String SEND = "/v1/projects/soundcheck-test/messages:send";
    static final PushMessage MESSAGE =
            new PushMessage("Soundcheck", "@sam liked your clip", Map.of("type", "POST_LIKED", "postId", "p1"));

    @Configuration(proxyBeanMethods = false)
    @EnableRetry(proxyTargetClass = true) // as in ResilienceConfig
    @Import(PushRetryConfig.class)
    static class Config {

        @Bean
        FcmClient fcmClient() {
            JsonMapper json = JsonMappers.builder().build();
            PushProperties properties = new PushProperties(true, "", google.baseUrl(), google.baseUrl() + "/token");
            FcmCredentials credentials = new FcmCredentials(
                    "soundcheck-test",
                    "push@soundcheck-test.iam.gserviceaccount.com",
                    "key-1",
                    (RSAPrivateKey) KEYS.getPrivate(),
                    "https://oauth2.googleapis.com/token");
            return new FcmClient(
                    properties, credentials, json, Clock.fixed(Instant.parse("2026-10-04T18:00:00Z"), ZoneOffset.UTC));
        }
    }

    @Autowired
    FcmClient fcm;

    @BeforeEach
    void googleAnswers() {
        fcm.forgetAccessToken();
        google.resetAll();
        google.stubFor(post(urlPathEqualTo("/token"))
                .willReturn(aResponse().withBody("{\"access_token\": \"ya29.test\", \"expires_in\": 3599}")));
    }

    @Test
    void signsInAsTheServiceAccountThenSendsTheMessageToTheDevice() throws Exception {
        google.stubFor(post(urlPathEqualTo(SEND)).willReturn(aResponse().withBody("{\"name\": \"m1\"}")));

        assertThat(fcm.send("device-1", MESSAGE)).isEqualTo(Outcome.SENT);
        assertThat(fcm.send("device-2", MESSAGE)).isEqualTo(Outcome.SENT);

        // One access token for both messages, asked for with a JWT the service account signed (RS256).
        LoggedRequest exchange =
                google.findAll(postRequestedFor(urlPathEqualTo("/token"))).getFirst();
        assertThat(google.findAll(postRequestedFor(urlPathEqualTo("/token")))).hasSize(1);
        Map<String, String> form = form(exchange.getBodyAsString());
        assertThat(form).containsEntry("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        SignedJWT assertion = SignedJWT.parse(form.get("assertion"));
        assertThat(assertion.verify(new RSASSAVerifier((RSAPublicKey) KEYS.getPublic())))
                .isTrue();
        assertThat(assertion.getHeader().getKeyID()).isEqualTo("key-1");
        assertThat(assertion.getJWTClaimsSet().getIssuer()).isEqualTo("push@soundcheck-test.iam.gserviceaccount.com");
        assertThat(assertion.getJWTClaimsSet().getStringClaim("scope")).isEqualTo(FcmClient.SCOPE);

        google.verify(postRequestedFor(urlPathEqualTo(SEND))
                .withHeader("Authorization", equalTo("Bearer ya29.test"))
                .withRequestBody(matchingJsonPath("$.message.token", equalTo("device-1")))
                .withRequestBody(matchingJsonPath("$.message.notification.body", equalTo("@sam liked your clip")))
                .withRequestBody(matchingJsonPath("$.message.data.postId", equalTo("p1")))
                .withRequestBody(matchingJsonPath("$.message.android.notification.channel_id", equalTo("activity"))));
    }

    @Test
    void aTokenFcmNoLongerKnowsIsReportedGone() {
        google.stubFor(
                post(urlPathEqualTo(SEND))
                        .willReturn(
                                aResponse()
                                        .withStatus(404)
                                        .withBody(
                                                """
                {"error": {"code": 404, "status": "NOT_FOUND",
                  "details": [{"@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", "errorCode": "UNREGISTERED"}]}}""")));
        assertThat(fcm.send("old-phone", MESSAGE)).isEqualTo(Outcome.TOKEN_GONE);

        google.stubFor(
                post(urlPathEqualTo(SEND))
                        .willReturn(
                                aResponse()
                                        .withStatus(400)
                                        .withBody(
                                                """
                {"error": {"code": 400, "status": "INVALID_ARGUMENT",
                  "message": "The registration token is not a valid FCM registration token"}}""")));
        assertThat(fcm.send("garbled", MESSAGE)).isEqualTo(Outcome.TOKEN_GONE);
    }

    @Test
    void busyIsRetriedAndARefusalIsNot() {
        google.stubFor(post(urlPathEqualTo(SEND))
                .inScenario("busy")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("ok"));
        google.stubFor(post(urlPathEqualTo(SEND))
                .inScenario("busy")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withBody("{\"name\": \"m2\"}")));
        assertThat(fcm.send("device-1", MESSAGE)).isEqualTo(Outcome.SENT);
        google.verify(2, postRequestedFor(urlPathEqualTo(SEND)));

        google.resetRequests();
        google.stubFor(post(urlPathEqualTo(SEND))
                .willReturn(aResponse().withStatus(403).withBody("{}")));
        assertThatThrownBy(() -> fcm.send("device-1", MESSAGE))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(TransientPushException.class);
        google.verify(1, postRequestedFor(urlPathEqualTo(SEND)));
    }

    private static Map<String, String> form(String body) {
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            fields.put(
                    URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return fields;
    }

    private static KeyPair keys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
