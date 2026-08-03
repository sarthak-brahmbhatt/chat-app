package com.chatapp.userservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and parses JWT access tokens (CLAUDE.md 3.3, build-order step 3).
 *
 * This is a new package, "security", separate from the entity/repository/dto/
 * service/controller/exception/config layers covered so far. That's deliberate:
 * this class isn't business logic (it doesn't decide *whether* someone should
 * get a token — AuthService does that) and it isn't really "config" either (it
 * does real work: signing and verifying tokens). It's a supporting utility,
 * the same conceptual role PasswordEncoder plays for hashing — infrastructure
 * that a service layer leans on, not a step in the controller -> service ->
 * repository chain itself.
 *
 * Library choice: io.jsonwebtoken (jjwt), not Spring Security's OAuth2 resource
 * server support (JwtEncoder/JwtDecoder, OAuth2TokenValidator, etc.). That
 * Spring Security machinery is built around delegating token issuance/
 * validation to a separate authorization server — precisely the OAuth2
 * delegation model CLAUDE.md 3.3 already rejected for this project (single
 * app, single org, nothing ever needs delegated third-party access). This
 * service mints and will verify its own tokens directly, so jjwt's plain
 * "build a signed JWT string / parse one back out" API is a direct fit,
 * without dragging in an API surface shaped around a use case this project
 * doesn't have. It's also a standalone library with zero Spring Security
 * auto-configuration attached — same reasoning as using bare
 * spring-security-crypto (see PasswordEncoderConfig) instead of the full
 * spring-boot-starter-security this early.
 */
@Component
public class JwtService {

    private final SecretKey signingKey;
    private final Duration accessTokenTtl;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiration-minutes}") long accessTokenExpirationMinutes
    ) {
        // HMAC-SHA algorithms need a key of at least 256 bits (32 bytes); jjwt
        // throws at startup if the configured secret is too short, which is
        // exactly the failure mode we want (fail loudly at boot, not silently
        // produce a weak/rejected token later). The key is derived once here,
        // not on every call to generateAccessToken/parseToken.
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = Duration.ofMinutes(accessTokenExpirationMinutes);
    }

    /**
     * Builds a signed access token for a successfully authenticated user.
     *
     * The subject ("sub") claim is the user's id, not their username: id is
     * the truly immutable identifier (a username is a value that could one day
     * be changed by the user; the numeric primary key never changes), so
     * anything that later trusts this token's identity is trusting a stable
     * value. Username is included as a separate claim purely for convenience
     * (readable at a glance when debugging a token) without making it the
     * thing other services key off of.
     */
    public String generateAccessToken(Long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Parses a token and verifies its signature and expiry, returning its
     * claims. No endpoint in this service calls this yet — both /register and
     * /login are intentionally public/unauthenticated, and the first endpoint
     * that will actually require a valid token (GET /users) is a later
     * build-order step. This method exists now anyway for two reasons: it's
     * the direct inverse of generateAccessToken and shares the same
     * signingKey, so it belongs next to it rather than being written
     * separately later; and it's what lets tests decode a real token returned
     * by /login and assert on its actual claims, instead of treating it as an
     * opaque string.
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Exposes the configured access-token lifetime in seconds, so
     * AuthService/LoginResponse can tell the client how long the token is
     * valid for without the client needing to decode the JWT itself just to
     * find that out.
     */
    public long getAccessTokenTtlSeconds() {
        return accessTokenTtl.toSeconds();
    }
}
