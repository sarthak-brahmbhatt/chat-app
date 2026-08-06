package com.chatapp.chatservice.dto;

/**
 * A single, consistent error body shape for chat-service's REST endpoints —
 * e.g. {"message": "Missing or malformed Authorization header"}. Mirrors
 * user-service's ErrorResponse of the exact same name/shape byte-for-byte
 * (not shared as a common module - see JwtValidator's class comment for the
 * same small-intentional-duplication reasoning applied here), so a client
 * reading an error from EITHER backend only ever needs to know one thing:
 * response.message.
 */
public class ErrorResponse {

    private final String message;

    public ErrorResponse(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
