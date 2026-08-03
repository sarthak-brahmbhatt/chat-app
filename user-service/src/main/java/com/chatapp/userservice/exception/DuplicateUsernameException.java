package com.chatapp.userservice.exception;

/**
 * Thrown by UserService when a registration attempt uses a username that's
 * already taken. A dedicated exception type (rather than e.g. a generic
 * RuntimeException with a message) lets GlobalExceptionHandler catch this
 * specific case and map it to a specific HTTP status (409 — see
 * GlobalExceptionHandler for the reasoning), separately from any other
 * failure.
 */
public class DuplicateUsernameException extends RuntimeException {

    public DuplicateUsernameException(String username) {
        super("Username '" + username + "' is already taken");
    }
}
