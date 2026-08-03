package com.chatapp.userservice.exception;

/**
 * Thrown by AuthService for BOTH failure cases on login: username doesn't
 * exist, and username exists but the password doesn't match. Deliberately
 * one exception type with one fixed message, not e.g. separate
 * UserNotFoundException / WrongPasswordException types — a per-case
 * exception, however cleanly typed, would tempt some future caller into
 * logging or returning the specific case, which is exactly the username-
 * enumeration leak this endpoint has to avoid. Making it structurally
 * impossible to say which one happened (there's only one exception, with no
 * constructor argument identifying which check failed) is stronger than
 * relying on every future caller to remember not to say more.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid username or password");
    }
}
