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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Authentication logic for login and refresh (CLAUDE.md 3.3, build-order
 * steps 3 and 10): verifying credentials, and issuing/rotating the
 * access+refresh token pair.
 *
 * This is a separate class from UserService rather than login()/refresh()
 * methods added there. UserService owns user *management* (registration,
 * profile changes); AuthService owns *authentication* — a different concern.
 *
 * AuthService talks to UserRepository directly rather than going through
 * UserService — there's nothing in UserService (duplicate-username checking,
 * registration-time hashing) that this needs, so routing through it would
 * only add indirection. Both services are allowed to depend on the same
 * repository; they represent different operations over the same data, not a
 * chain of responsibility between the two service classes.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    // A real bcrypt hash of a fixed, never-used-elsewhere dummy password,
    // computed once at startup. Its only purpose is to give
    // passwordEncoder.matches() something to do — see the comment in login()
    // for why.
    private final String dummyPasswordHash;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.dummyPasswordHash = passwordEncoder.encode("auth-service-timing-safety-dummy-password");
    }

    /**
     * Verifies credentials and, if valid, issues an access token AND a
     * refresh token (build-order step 10 — a new session/token family, see
     * RefreshTokenService.issueForNewLogin).
     *
     * Both failure cases — username not found, and username found but
     * password wrong — throw the exact same InvalidCredentialsException with
     * the exact same message. That alone stops an attacker from
     * distinguishing the two cases by reading the response *body*. But an
     * earlier, naive version of this method (return immediately if the user
     * isn't found, only call passwordEncoder.matches for existing users)
     * would still leak the same information through *timing*: bcrypt
     * verification is deliberately slow (~100ms), so a nonexistent-username
     * request would return almost instantly while a wrong-password request
     * would take ~100ms longer — an attacker could enumerate valid usernames
     * just by measuring response time, even with identical error text.
     *
     * To close that gap, passwordEncoder.matches() is called exactly once on
     * every login attempt, regardless of whether the username exists — against
     * the real stored hash if the user was found, or against a fixed dummy
     * hash otherwise — so every failing request pays the same bcrypt cost.
     */
    public LoginResponse login(LoginRequest request) {
        Optional<User> maybeUser = userRepository.findByUsername(request.getUsername());
        String hashToCheck = maybeUser.map(User::getPassword).orElse(dummyPasswordHash);

        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), hashToCheck);

        if (maybeUser.isEmpty() || !passwordMatches) {
            throw new InvalidCredentialsException();
        }

        User user = maybeUser.get();
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getUsername());
        // String.valueOf, not user.getId().toString() — matches how
        // JwtService itself builds the subject claim above, and avoids an
        // NPE if getId() were ever null (never true for a real, freshly-
        // loaded user, but not a guarantee worth relying on directly).
        String refreshToken = refreshTokenService.issueForNewLogin(String.valueOf(user.getId()));
        return new LoginResponse(accessToken, refreshToken, jwtService.getAccessTokenTtlSeconds());
    }

    /**
     * Rotates a refresh token (build-order step 10): validates it against
     * RefreshTokenService (which throws InvalidRefreshTokenException for
     * every way it can be unusable — not found, expired, or a detected
     * replay of an already-rotated token), then issues a brand-new
     * access+refresh pair.
     *
     * A DB lookup for the current username (rather than trusting a username
     * cached in Redis at original login time) is deliberate: it's a single
     * cheap indexed read, and it means the new access token always reflects
     * the user's CURRENT state, the same guarantee a fresh login already
     * gives — not a username that could be up to refresh-token-lifetime
     * (days) stale if it were ever allowed to change.
     */
    public RefreshResponse refresh(String presentedRefreshToken) {
        RefreshTokenService.RotatedTokens rotated = refreshTokenService.rotate(presentedRefreshToken);

        User user = userRepository.findById(Long.parseLong(rotated.userId()))
                .orElseThrow(InvalidRefreshTokenException::new);

        String accessToken = jwtService.generateAccessToken(user.getId(), user.getUsername());
        return new RefreshResponse(accessToken, rotated.refreshToken(), jwtService.getAccessTokenTtlSeconds());
    }
}
