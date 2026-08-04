package com.chatapp.userservice.controller;

import com.chatapp.userservice.dto.LoginRequest;
import com.chatapp.userservice.dto.LoginResponse;
import com.chatapp.userservice.dto.RefreshRequest;
import com.chatapp.userservice.dto.RefreshResponse;
import com.chatapp.userservice.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP layer for login and refresh — separate from UserController,
 * mirroring the AuthService/UserService split (see AuthService for the
 * reasoning). This class stays as narrow as UserController: parse the
 * request, trigger validation, delegate to AuthService, translate the
 * result into a status code.
 */
@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * POST /login — CLAUDE.md section 2 / 4, build-order step 3 (now also
     * returning a refresh token, build-order step 10).
     *
     * @Valid catches a blank username/password (400) before this method body
     * runs, same mechanism as /register. Anything past that point — wrong
     * username, wrong password — is AuthService's job; this method only
     * handles the success path and lets InvalidCredentialsException propagate
     * to GlobalExceptionHandler for the 401 case.
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /refresh — CLAUDE.md 3.3, build-order step 10.
     *
     * Deliberately NOT protected by JwtAuthenticationInterceptor (see
     * WebMvcConfig's exclusion list) — a client calls this precisely BECAUSE
     * their access token has expired, so by definition they have no valid
     * Authorization header to present. Authentication for this endpoint
     * happens via the refresh token in the request BODY instead, verified
     * against Redis inside AuthService/RefreshTokenService.
     */
    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        RefreshResponse response = authService.refresh(request.getRefreshToken());
        return ResponseEntity.ok(response);
    }
}
