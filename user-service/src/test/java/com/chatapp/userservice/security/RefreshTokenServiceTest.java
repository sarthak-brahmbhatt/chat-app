package com.chatapp.userservice.security;

import com.chatapp.userservice.exception.InvalidRefreshTokenException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * A plain Mockito unit test for RefreshTokenService — the class that
 * actually implements CLAUDE.md 3.3's rotation + reuse-detection design
 * (build-order step 10). See its class comment for the full reasoning this
 * test verifies.
 *
 * StringRedisTemplate is mocked, but backed by a real in-memory HashMap
 * (fakeStore below) rather than stubbed with fixed per-call return values —
 * this class's behavior is inherently STATEFUL across calls (issue, then
 * rotate, then rotate the SAME stale token again to trigger reuse
 * detection), and token/hash values are randomly generated at runtime, not
 * predictable in advance. A real backing map is what makes testing that
 * sequence meaningful instead of hand-stubbing exact unpredictable
 * arguments per call.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final long EXPIRATION_DAYS = 7;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final Map<String, String> fakeStore = new HashMap<>();

    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        fakeStore.clear();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        lenient().doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            fakeStore.put(key, value);
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        lenient().when(valueOperations.get(anyString()))
                .thenAnswer(invocation -> fakeStore.get((String) invocation.getArgument(0)));

        lenient().when(redisTemplate.delete(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return fakeStore.remove(key) != null;
        });

        // A fixed positive "remaining TTL" for markRotated's TTL-preserving
        // re-write — the exact value doesn't matter for these tests, only
        // that it's a real positive number so that code path runs normally.
        lenient().when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(600L);

        refreshTokenService = new RefreshTokenService(redisTemplate, new ObjectMapper(), EXPIRATION_DAYS);
    }

    @Test
    void issueForNewLogin_thenRotate_succeedsAndReturnsANewDifferentToken() {
        String original = refreshTokenService.issueForNewLogin("user-1");

        RefreshTokenService.RotatedTokens rotated = refreshTokenService.rotate(original);

        assertThat(rotated.userId()).isEqualTo("user-1");
        assertThat(rotated.refreshToken()).isNotBlank();
        assertThat(rotated.refreshToken()).isNotEqualTo(original);
    }

    @Test
    void rotate_withNeverIssuedToken_throwsInvalidRefreshTokenException() {
        // Also stands in for a genuinely EXPIRED token: once a key's TTL
        // elapses, Redis simply returns null for it — indistinguishable
        // from a key that was never set in the first place. That's exactly
        // why "an expired one is simply gone, no manual cleanup" needs no
        // special-casing in RefreshTokenService's code, and so needs none
        // here either — this one test genuinely covers both cases.
        assertThrows(InvalidRefreshTokenException.class,
                () -> refreshTokenService.rotate("a-token-that-was-never-issued-or-has-since-expired"));
    }

    @Test
    void rotate_withAlreadyRotatedToken_throwsInvalidRefreshTokenExceptionDetectingReuse() {
        String original = refreshTokenService.issueForNewLogin("user-1");
        refreshTokenService.rotate(original); // legitimate use — consumes it

        // Presenting the SAME (now-stale) token again simulates an attacker
        // replaying a stolen refresh token after the legitimate user has
        // already moved on to the next one in the chain.
        assertThrows(InvalidRefreshTokenException.class, () -> refreshTokenService.rotate(original));
    }

    @Test
    void rotate_afterReuseDetected_alsoRevokesTheCurrentlyValidTokenForThatFamily() {
        // The more important half of reuse detection: not just rejecting
        // the replayed token, but killing the WHOLE chain — including the
        // token that legitimately replaced it, which the real user (or the
        // attacker, we can't tell which) would otherwise still be holding.
        String original = refreshTokenService.issueForNewLogin("user-1");
        RefreshTokenService.RotatedTokens firstRotation = refreshTokenService.rotate(original);

        assertThrows(InvalidRefreshTokenException.class, () -> refreshTokenService.rotate(original));

        // The token issued by the FIRST (legitimate) rotation above must now
        // ALSO be rejected — proving the whole family was revoked, not just
        // the replayed token.
        assertThrows(InvalidRefreshTokenException.class, () -> refreshTokenService.rotate(firstRotation.refreshToken()));
    }

    @Test
    void issueForNewLogin_forTwoDifferentUsers_producesIndependentTokens() {
        // A sanity check that families/tokens aren't accidentally shared or
        // collide across different users' sessions.
        String tokenForUser1 = refreshTokenService.issueForNewLogin("user-1");
        String tokenForUser2 = refreshTokenService.issueForNewLogin("user-2");

        assertThat(tokenForUser1).isNotEqualTo(tokenForUser2);
        assertThat(refreshTokenService.rotate(tokenForUser1).userId()).isEqualTo("user-1");
        assertThat(refreshTokenService.rotate(tokenForUser2).userId()).isEqualTo("user-2");
    }
}
