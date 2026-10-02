package com.achintha.productservice.support;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.interfaces.RSAPublicKey;

/**
 * user-service stand-in for the whole test run: serves the JWKS of {@link TestKeys} and, per test, the internal
 * service-token and security-state endpoints.
 */
public final class SharedWireMock {

    public static final String KEY_ID = "test-key";
    public static final WireMockServer USER_SERVICE = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        USER_SERVICE.start();
        stubJwks();
    }

    private SharedWireMock() {
    }

    public static void stubJwks() {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) TestKeys.KEY_PAIR.getPublic())
                .keyID(KEY_ID)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
        USER_SERVICE.stubFor(get(urlEqualTo("/.well-known/jwks.json"))
                .willReturn(okJson(new JWKSet(key).toString())));
    }

    public static String jwksUri() {
        return USER_SERVICE.baseUrl() + "/.well-known/jwks.json";
    }
}
