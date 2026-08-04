package com.chatapp.userservice.exception;

/**
 * Thrown by RefreshTokenService for EVERY way a presented refresh token can
 * fail to be usable: it doesn't exist, it expired naturally, or it was
 * already rotated away and is now being replayed (detected reuse — see
 * RefreshTokenService's class comment). Deliberately one exception, one
 * fixed message, no constructor argument identifying which case occurred —
 * the same structural reasoning as InvalidCredentialsException: a caller
 * gets no signal distinguishing "garbage token" from "we just caught you
 * replaying a stolen one," which matters because the second case is exactly
 * when you'd least want to tip off whoever's holding that token.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("Invalid refresh token");
    }
}
