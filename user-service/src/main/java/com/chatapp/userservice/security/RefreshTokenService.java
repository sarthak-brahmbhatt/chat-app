package com.chatapp.userservice.security;

import com.chatapp.userservice.exception.InvalidRefreshTokenException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The Redis-backed refresh-token registry (CLAUDE.md 3.3, build-order step
 * 10): issuing, rotating, and revoking refresh tokens.
 *
 * DESIGN OVERVIEW — read this before touching rotate() or revokeFamily():
 *
 * A refresh token is an opaque, cryptographically random 256-bit value —
 * NOT a JWT. Unlike the access token (which needs to be stateless and
 * self-verifying, since it's checked on every single request), a refresh
 * token is checked against a store on the rare occasions it's actually
 * used (CLAUDE.md 3.3: "checked against a revocable registry"), so there's
 * nothing to gain from making it a signed, self-contained token — an opaque
 * value that's meaningless without a Redis lookup is simpler and can't be
 * tampered with (there are no claims in it to forge).
 *
 * Two Redis key families:
 *   - refresh-token:&lt;hash&gt;  -> JSON {userId, familyId, rotated}, TTL = the
 *     refresh token lifetime.
 *   - refresh-family:&lt;familyId&gt; -> the hash of whichever token is CURRENTLY
 *     valid for that family, same TTL. Exists purely so a detected replay
 *     can find and revoke "whatever's currently valid for this chain,"
 *     without scanning all tokens.
 *
 * We store SHA-256(token) as the key, never the raw token — a fast,
 * un-salted hash, deliberately NOT bcrypt (contrast with password hashing
 * in UserService). This is a different problem: a password is a low-entropy
 * human-chosen secret vulnerable to dictionary/brute-force attack, which is
 * exactly what bcrypt's deliberate slowness defends against. This token is
 * already 256 bits of SecureRandom output — nothing to brute-force, so a
 * fast hash loses nothing. The reason to hash at all: if Redis itself is
 * ever compromised (a leaked backup, misconfigured access), an attacker
 * with read access sees only hashes, not directly-usable bearer
 * credentials — worth the one extra hash call given this token lives for
 * DAYS, a much larger exposure window than the access token's 15 minutes.
 *
 * ROTATION + REUSE DETECTION: on a successful rotate(), the OLD token isn't
 * deleted — its record is updated in place with rotated=true and left to
 * expire naturally. This is what makes reuse detectable at all: if that
 * exact token is presented again later, we can tell "this was valid and
 * got rotated away" apart from "this never existed" or "this expired." A
 * plain delete-on-rotate would make both cases look identical (not found),
 * losing the signal entirely.
 *
 * When reuse IS detected, the ENTIRE FAMILY is revoked — not just the
 * replayed token rejected. Reasoning: once a token has been used twice, we
 * cannot tell from here whether the legitimate user or an attacker is
 * holding the CURRENT valid token for that chain (whoever rotated first
 * "won" that round, but we don't know who that was). The safe response is
 * to invalidate the whole chain and force a fresh login — this bounds a
 * detected compromise to "one wasted round trip," rather than leaving a
 * window where an attacker's already-rotated-forward token might still work.
 */
@Service
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32; // 256 bits

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration tokenTtl;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${refresh-token.expiration-days}") long expirationDays) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.tokenTtl = Duration.ofDays(expirationDays);
    }

    /** Starts a brand-new session chain (a fresh family) for a successful login. */
    public String issueForNewLogin(String userId) {
        return issueToken(userId, UUID.randomUUID().toString());
    }

    /**
     * The result of a successful rotation: the same user, plus the new
     * refresh token replacing the one that was just consumed.
     */
    public record RotatedTokens(String userId, String refreshToken) {
    }

    /**
     * Validates a presented refresh token and, if it's genuinely still
     * valid (found, and not already rotated), rotates it: the old token is
     * marked rotated (kept, not deleted — see class comment) and a new
     * token is issued for the same family. Throws
     * InvalidRefreshTokenException — the SAME exception, with the SAME
     * message, whether the token simply doesn't exist/has expired, OR
     * reuse was just detected and the family got revoked. This mirrors
     * AuthService.login()'s identical-message-for-different-causes
     * pattern: a caller (legitimate or not) gets no signal distinguishing
     * "your token was garbage" from "we just caught you replaying a
     * rotated-away token and nuked your session" — only the server logs
     * (see the reuse branch below) know which happened.
     */
    public RotatedTokens rotate(String presentedToken) {
        String hash = hash(presentedToken);
        TokenRecord record = readRecord(hash);

        if (record == null) {
            throw new InvalidRefreshTokenException();
        }

        if (record.rotated()) {
            revokeFamily(record.familyId());
            throw new InvalidRefreshTokenException();
        }

        markRotated(hash, record);
        String newToken = issueToken(record.userId(), record.familyId());
        return new RotatedTokens(record.userId(), newToken);
    }

    private String issueToken(String userId, String familyId) {
        String token = generateOpaqueToken();
        String hash = hash(token);
        writeRecord(hash, new TokenRecord(userId, familyId, false), tokenTtl);
        redisTemplate.opsForValue().set(familyKey(familyId), hash, tokenTtl);
        return token;
    }

    private void markRotated(String hash, TokenRecord record) {
        // Preserve whatever TTL this record already had, rather than
        // resetting it to the full lifetime — its only remaining job is
        // detecting a replay of THIS exact token, which only matters for as
        // long as this token would have been valid anyway.
        Long remainingSeconds = redisTemplate.getExpire(tokenKey(hash), TimeUnit.SECONDS);
        Duration remaining = (remainingSeconds != null && remainingSeconds > 0)
                ? Duration.ofSeconds(remainingSeconds)
                : tokenTtl;
        writeRecord(hash, new TokenRecord(record.userId(), record.familyId(), true), remaining);
    }

    /**
     * Revokes an entire token family: deletes both the currently-valid
     * token for that family and the family pointer itself. This is the
     * "kill the whole chain" response to detected reuse (see class
     * comment) — after this, EVERY refresh token ever issued for this
     * family (including the one an attacker might currently be holding)
     * stops working, and the legitimate user has to log in again.
     */
    private void revokeFamily(String familyId) {
        String familyKey = familyKey(familyId);
        String currentHash = redisTemplate.opsForValue().get(familyKey);
        if (currentHash != null) {
            redisTemplate.delete(tokenKey(currentHash));
        }
        redisTemplate.delete(familyKey);
    }

    private TokenRecord readRecord(String hash) {
        String json = redisTemplate.opsForValue().get(tokenKey(hash));
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, TokenRecord.class);
        } catch (JsonProcessingException e) {
            // An unreadable value shouldn't be possible (we're the only
            // writer), but if it ever happened, treating it as "not found"
            // is the correct, safe fallback — same 401 either way, no 500
            // for what's ultimately still "this refresh token isn't usable."
            return null;
        }
    }

    private void writeRecord(String hash, TokenRecord record, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(tokenKey(hash), objectMapper.writeValueAsString(record), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize refresh token record", e);
        }
    }

    private String tokenKey(String hash) {
        return "refresh-token:" + hash;
    }

    private String familyKey(String familyId) {
        return "refresh-family:" + familyId;
    }

    private String generateOpaqueToken() {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory algorithm on every standard JVM (a JCA
            // requirement) — this genuinely cannot happen, but the checked
            // exception still has to go somewhere.
            throw new IllegalStateException(e);
        }
    }

    /** The internal Redis-storage shape — never exposed outside this class. */
    private record TokenRecord(String userId, String familyId, boolean rotated) {
    }
}
