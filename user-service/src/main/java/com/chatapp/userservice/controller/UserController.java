package com.chatapp.userservice.controller;

import com.chatapp.userservice.dto.RegisterRequest;
import com.chatapp.userservice.dto.RegisterResponse;
import com.chatapp.userservice.dto.UserListResponse;
import com.chatapp.userservice.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The "controller" layer: the only place in this feature that knows about
 * HTTP. Its job is narrow on purpose — deserialize the request, trigger
 * validation, delegate to UserService for the actual work, and translate the
 * result into a status code + body. No business logic (duplicate checks,
 * password hashing) lives here; see UserService for that.
 */
@RestController
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * POST /register — CLAUDE.md section 2 / 4.
     *
     * @Valid tells Spring to run RegisterRequest's Bean Validation annotations
     * (@NotBlank on username/password/firstName) before this method body runs.
     * If validation fails, Spring throws MethodArgumentNotValidException
     * before we ever get here — GlobalExceptionHandler turns that into the
     * 400 response, so this method only has to handle the success path and
     * let DuplicateUsernameException (thrown by UserService) propagate up to
     * the same handler for the 409 case.
     */
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegisterResponse response = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /users — CLAUDE.md section 2, build-order step 4.
     *
     * No @Valid/request body here — there's nothing to validate on a GET with
     * no body. The actual gatekeeping for this endpoint (is there a valid
     * JWT?) already happened before this method runs, in
     * JwtAuthenticationInterceptor's preHandle — this method only executes at
     * all for requests that already passed that check, so it can go straight
     * to fetching data.
     */
    @GetMapping("/users")
    public ResponseEntity<UserListResponse> listUsers() {
        return ResponseEntity.ok(userService.listUsers());
    }
}
