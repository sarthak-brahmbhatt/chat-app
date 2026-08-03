package com.chatapp.userservice.dto;

/**
 * A single, consistent error body shape for every error this service returns
 * (400 for missing fields, 409 for duplicate username, etc.) — e.g.
 * {"message": "username is required"}. Having one shape for all errors means
 * a client only ever needs to know one thing to read: response.message.
 */
public class ErrorResponse {

    private String message;

    public ErrorResponse(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
