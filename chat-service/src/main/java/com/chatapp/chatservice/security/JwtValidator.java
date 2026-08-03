package com.chatapp.chatservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Verifies JWT access tokens issued by user-service (CLAUDE.md 3.3,
 * build-order step 5). This is deliberately NOT the same class as
 * user-service's JwtService — chat-service never issues tokens, only checks
 * ones it receives, so there's no generateAccessToken/TTL-exposure logic
 * here at all. It's a small, intentional duplication of user-service's
 * verification logic (~15 lines) rather than a shared Gradle module between
 * the two services; see the explanation given alongside this change for why
 * that tradeoff was made this way for now.
 *
 * The signing key is derived from jwt.secret, which MUST resolve to the
 * exact same value user-service uses — see the jwt.secret comment in this
 * service's application.yml for how that's kept true across Docker Compose
 * and native runs.
 */
@Component
public class JwtValidator {

    private final SecretKey signingKey;

    public JwtValidator(@Value("${jwt.secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parses a token and verifies its signature and expiry, returning its
     * claims. Throws JwtException (expired/malformed/bad signature — see
     * io.jsonwebtoken's exception hierarchy) or IllegalArgumentException (a
     * blank/empty token string) if the token isn't valid in any way. Callers
     * decide what "invalid" means for them — here, ChatWebSocketHandler
     * closes the WebSocket connection.
     */
    public Claims validate(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
