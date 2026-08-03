package com.chatapp.userservice.service;

import com.chatapp.userservice.dto.LoginRequest;
import com.chatapp.userservice.dto.LoginResponse;
import com.chatapp.userservice.entity.User;
import com.chatapp.userservice.exception.InvalidCredentialsException;
import com.chatapp.userservice.repository.UserRepository;
import com.chatapp.userservice.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Authentication logic for login (CLAUDE.md 3.3, build-order step 3):
 * verifying credentials and issuing a JWT access token.
 *
 * This is a separate class from UserService rather than a login() method
 * added there. UserService owns user *management* (registration today,
 * likely profile changes later); AuthService owns *authentication* — a
 * different concern that's about to grow on its own (refresh tokens + the
 * revocation registry, CLAUDE.md 3.3 / build-order step 9). Splitting them
 * now means that growth lands in a class dedicated to it, instead of bloating
 * an unrelated one.
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

    // A real bcrypt hash of a fixed, never-used-elsewhere dummy password,
    // computed once at startup. Its only purpose is to give
    // passwordEncoder.matches() something to do — see the comment in login()
    // for why.
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyPasswordHash = passwordEncoder.encode("auth-service-timing-safety-dummy-password");
    }

    /**
     * Verifies credentials and, if valid, issues an access token.
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
        return new LoginResponse(accessToken, jwtService.getAccessTokenTtlSeconds());
    }
}
