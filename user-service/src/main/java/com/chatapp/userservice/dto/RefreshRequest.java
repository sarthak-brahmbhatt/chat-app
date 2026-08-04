package com.chatapp.userservice.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The JSON body shape for POST /refresh (build-order step 10). Same
 * mutable-JavaBean-plus-@NotBlank shape as LoginRequest/RegisterRequest —
 * required for the same reason: @Valid + Bean Validation populates these
 * via a no-arg constructor and setters, not a record's canonical constructor.
 */
public class RefreshRequest {

    @NotBlank(message = "refreshToken is required")
    private String refreshToken;

    public RefreshRequest() {
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
