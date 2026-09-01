package com.shop.gateway.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Test-only JWKS/JWT helper: generates an RSA key the gateway can validate
 * against and mints signed tokens with realm roles.
 */
public final class TestKeys {

    public static final String JWKS_PATH = "/realms/test/protocol/openid-connect/certs";

    private static final KeyPair KEY_PAIR = generate();

    private TestKeys() {
    }

    public static RSAKey rsaKey() {
        return new RSAKey.Builder((RSAPublicKey) KEY_PAIR.getPublic())
                .privateKey((RSAPrivateKey) KEY_PAIR.getPrivate())
                .keyID("test-key")
                .build();
    }

    public static String jwksJson(RSAKey key) {
        return new JWKSet(key).toString();
    }

    public static String signedToken(RSAKey key, String issuer, List<String> realmRoles) {
        final var claims = new JWTClaimsSet.Builder()
                .subject("11111111-1111-1111-1111-111111111111")
                .issuer(issuer)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .claim("realm_access", Map.of("roles", realmRoles))
                .build();
        return signed(key, claims);
    }

    /**
     * Minimal OIDC discovery document so the gateway's issuer-uri decoder can
     * resolve the JWKS from the WireMock instance.
     */
    public static String oidcConfigurationJson(String issuer, String jwksUri) {
        return "{\"issuer\":\"" + issuer + "\","
                + "\"jwks_uri\":\"" + jwksUri + "\"}";
    }

    public static String signedClientToken(RSAKey key, String clientId, List<String> clientRoles) {
        final var claims = new JWTClaimsSet.Builder()
                .subject(clientId)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .claim("resource_access", Map.of(clientId, Map.of("roles", clientRoles)))
                .build();
        return signed(key, claims);
    }

    private static String signed(RSAKey key, JWTClaimsSet claims) {
        try {
            final var jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(key.toPrivateKey()));
            return jwt.serialize();
        } catch (final JOSEException e) {
            throw new IllegalStateException("Could not sign test token", e);
        }
    }

    private static KeyPair generate() {
        try {
            final var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA unavailable", e);
        }
    }
}
