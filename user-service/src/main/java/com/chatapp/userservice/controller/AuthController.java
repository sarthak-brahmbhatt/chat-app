package com.chatapp.userservice.controller;

import com.chatapp.userservice.dto.LoginRequest;
import com.chatapp.userservice.dto.LoginResponse;
import com.chatapp.userservice.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP layer for login — separate from UserController, mirroring the
 * AuthService/UserService split (see AuthService for the reasoning). This
 * class stays as narrow as UserController: parse the request, trigger
 * validation, delegate to AuthService, translate the result into a status
 * code. It never sees the distinction between "no such user" and "wrong
 * password" — AuthService only ever hands it one outcome (a LoginResponse) or
 * one exception (InvalidCredentialsException), so there's nothing here that
 * could accidentally leak that distinction even by mistake.
 */
@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * POST /login — CLAUDE.md section 2 / 4, build-order step 3.
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
}
