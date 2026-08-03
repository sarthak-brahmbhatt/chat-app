package com.chatapp.userservice.exception;

import com.chatapp.userservice.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Centralizes error -> HTTP response mapping for every controller in this
 * service (not just UserController), so individual controller methods don't
 * need try/catch blocks — they just let exceptions propagate, and Spring
 * routes them here based on exception type.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Fired when @Valid on RegisterRequest finds a blank required field
     * (username, password, or firstName). Maps to 400 Bad Request: the
     * request itself is malformed/incomplete, which is exactly what 400
     * means.
     *
     * A request can fail multiple field constraints at once (e.g. both
     * username and password missing) — all of their messages are joined into
     * one string rather than only surfacing the first, so the client sees
     * everything wrong in one response instead of fixing one field at a time.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ErrorResponse(message));
    }

    /**
     * Fired by UserService when the requested username is already taken.
     *
     * Mapped to 409 Conflict, not 400: per HTTP semantics (RFC 7231 §6.5.8),
     * 409 specifically means "the request conflicts with the current state
     * of the target resource" — which is precisely what a duplicate username
     * is. 400 is reserved for a request that's malformed or fails validation
     * on its own terms (missing fields, wrong types); this request is
     * perfectly well-formed, it just can't be satisfied given what already
     * exists in the User DB. Distinguishing the two also lets a client tell
     * "fix your input" (400) apart from "pick a different username" (409)
     * without parsing the message body.
     */
    @ExceptionHandler(DuplicateUsernameException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateUsername(DuplicateUsernameException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(ex.getMessage()));
    }

    /**
     * Fired by AuthService on login, for both "no such username" and
     * "username exists but password is wrong" — see InvalidCredentialsException
     * for why those two cases are deliberately collapsed into one exception
     * with one fixed message before they ever reach here.
     *
     * Mapped to 401 Unauthorized, not 400/403: 401 specifically means "you did
     * not authenticate successfully" (RFC 7235 §3.1) — exactly this case. 403
     * would imply the caller successfully authenticated but isn't allowed to
     * do this specific thing, which doesn't apply here since authentication
     * itself is what failed.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(ex.getMessage()));
    }
}
