package com.chatapp.userservice.service;

import com.chatapp.userservice.dto.LoginRequest;
import com.chatapp.userservice.dto.LoginResponse;
import com.chatapp.userservice.dto.RefreshResponse;
import com.chatapp.userservice.entity.User;
import com.chatapp.userservice.exception.InvalidCredentialsException;
import com.chatapp.userservice.exception.InvalidRefreshTokenException;
import com.chatapp.userservice.repository.UserRepository;
import com.chatapp.userservice.security.JwtService;
import com.chatapp.userservice.security.RefreshTokenService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A plain Mockito unit test for AuthService — the credential-verification
 * logic that /login actually relies on, as opposed to AuthControllerTest,
 * which only checks that the controller reacts correctly to whatever
 * AuthService (mocked there) returns or throws.
 *
 * Unlike UserServiceTest, this class does NOT use @InjectMocks. @InjectMocks
 * only auto-wires fields annotated @Mock/@Spy, and this test deliberately
 * needs a real, non-mocked JwtService — a mock would let us assert "was
 * generateAccessToken called," but couldn't produce a real signed JWT for the
 * success test to actually decode and inspect. So AuthService is constructed
 * by hand in setUp() instead, same effect, just explicit about it.
 *
 * PasswordEncoder is a @Spy wrapping a real BCryptPasswordEncoder — same
 * reasoning as UserServiceTest: real hashing behavior (so a wrong password
 * genuinely fails to match), while still verifiable with Mockito's verify().
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String CORRECT_PASSWORD = "correct-horse-battery-staple";
    private static final long ACCESS_TOKEN_EXPIRATION_MINUTES = 15;

    @Mock
    private UserRepository userRepository;

    @Spy
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // RefreshTokenService is mocked directly here, not backed by a real Redis
    // double the way JwtService is real crypto — its own rotation/reuse-
    // detection/family-revocation logic is thoroughly covered by
    // RefreshTokenServiceTest instead. This class only needs to verify
    // AuthService's OWN job: does it call RefreshTokenService correctly and
    // assemble the response from what comes back.
    @Mock
    private RefreshTokenService refreshTokenService;

    private JwtService jwtService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        // A fixed, sufficiently long (>=32 byte) test-only secret. Real
        // JwtService, not mocked, so the success test below can decode an
        // actual token instead of trusting a canned mock return value.
        jwtService = new JwtService("test-only-jwt-signing-secret-at-least-32-bytes-long-xyz", ACCESS_TOKEN_EXPIRATION_MINUTES);
        authService = new AuthService(userRepository, passwordEncoder, jwtService, refreshTokenService);
    }

    private User existingUser() {
        return new User("alice", passwordEncoder.encode(CORRECT_PASSWORD), "Alice", "Smith");
    }

    @Test
    void login_withValidCredentials_returnsAccessTokenWithExpectedClaims() {
        User user = existingUser();
        // user.getId() is null here — id is only ever assigned by the DB on a
        // real save(), and (by design, see entity/User.java) there's no
        // setter to fake one on a test double. That's fine for this test:
        // the assertions below only check the username claim and expiry, not
        // the subject claim, so a missing id doesn't affect what's verified.
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(refreshTokenService.issueForNewLogin(anyString())).thenReturn("a-fresh-refresh-token");

        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword(CORRECT_PASSWORD);

        LoginResponse response = authService.login(request);

        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(response.getRefreshToken()).isEqualTo("a-fresh-refresh-token");
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        assertThat(response.getExpiresInSeconds()).isEqualTo(ACCESS_TOKEN_EXPIRATION_MINUTES * 60);

        Claims claims = jwtService.parseToken(response.getAccessToken());
        assertThat(claims.get("username", String.class)).isEqualTo("alice");
        assertThat(claims.getExpiration()).isAfter(new Date());
    }

    @Test
    void login_withWrongPassword_throwsInvalidCredentialsException() {
        // existingUser() is computed first, into a local variable, rather than
        // inline as when(...).thenReturn(Optional.of(existingUser())). Calling
        // it inline would invoke the passwordEncoder spy (via encode()) while
        // the userRepository stub is still "open" (when() called, thenReturn()
        // not yet attached) — Mockito's stubbing state machine doesn't allow
        // interacting with a different mock/spy in that gap and throws
        // UnfinishedStubbingException. Computing the value first sidesteps it.
        User user = existingUser();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("totally-wrong-password");

        InvalidCredentialsException exception =
                assertThrows(InvalidCredentialsException.class, () -> authService.login(request));

        assertThat(exception.getMessage()).isEqualTo("Invalid username or password");
    }

    @Test
    void login_withNonExistentUsername_throwsSameExceptionAndMessageAsWrongPassword() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        LoginRequest request = new LoginRequest();
        request.setUsername("ghost");
        request.setPassword("whatever");

        InvalidCredentialsException exception =
                assertThrows(InvalidCredentialsException.class, () -> authService.login(request));

        // Identical message to the wrong-password case above — asserted as a
        // literal string, not just "an exception was thrown," specifically to
        // lock in the anti-enumeration requirement: nothing about this
        // message may ever differ based on which check actually failed.
        assertThat(exception.getMessage()).isEqualTo("Invalid username or password");
    }

    @Test
    void login_withNonExistentUsername_stillInvokesPasswordEncoderMatches() {
        // This is the timing-safety guarantee described in AuthService.login:
        // even though there's no real stored hash to check against, matches()
        // must still be called once (against the internal dummy hash) so a
        // nonexistent-username request costs the same bcrypt time as a
        // wrong-password request. If a future refactor "optimized" this by
        // early-returning before calling matches() for an unknown user, this
        // test fails and catches the regression.
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        LoginRequest request = new LoginRequest();
        request.setUsername("ghost");
        request.setPassword("whatever");

        assertThrows(InvalidCredentialsException.class, () -> authService.login(request));

        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
    }

    @Test
    void refresh_withValidToken_returnsNewAccessTokenAndTheRotatedRefreshToken() {
        User user = existingUser();
        when(refreshTokenService.rotate("old-refresh-token"))
                .thenReturn(new RefreshTokenService.RotatedTokens("7", "new-refresh-token"));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        RefreshResponse response = authService.refresh("old-refresh-token");

        assertThat(response.getAccessToken()).isNotBlank();
        // The exact token RefreshTokenService.rotate(...) handed back — this
        // is deliberately just plumbed through, not regenerated here.
        assertThat(response.getRefreshToken()).isEqualTo("new-refresh-token");
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        assertThat(response.getExpiresInSeconds()).isEqualTo(ACCESS_TOKEN_EXPIRATION_MINUTES * 60);

        Claims claims = jwtService.parseToken(response.getAccessToken());
        assertThat(claims.get("username", String.class)).isEqualTo("alice");
    }

    @Test
    void refresh_withInvalidToken_propagatesInvalidRefreshTokenException() {
        // RefreshTokenService.rotate(...) is where "not found," "expired,"
        // and "reuse detected" all collapse into ONE exception (see
        // RefreshTokenServiceTest for those cases in detail) — from
        // AuthService's side, all it needs to do is let that exception
        // propagate untouched, which this test locks in.
        when(refreshTokenService.rotate("stale-token")).thenThrow(new InvalidRefreshTokenException());

        assertThrows(InvalidRefreshTokenException.class, () -> authService.refresh("stale-token"));
    }
}
