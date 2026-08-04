package com.chatapp.userservice.dto;

/**
 * The success response body for POST /refresh (200 OK) — structurally
 * identical to LoginResponse today (accessToken, tokenType, expiresInSeconds,
 * plus refreshToken), but deliberately its OWN type rather than reusing
 * LoginResponse. Same reasoning as UserSummary vs. RegisterResponse
 * (build-order step 4): "you just logged in" and "you just rotated your
 * token" are different operations that happen to return the same shape of
 * data today but represent different contracts, free to diverge later
 * (e.g. a login response could reasonably grow user-profile fields a
 * refresh response never would).
 */
public class RefreshResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    private long expiresInSeconds;

    public RefreshResponse(String accessToken, String refreshToken, long expiresInSeconds) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresInSeconds = expiresInSeconds;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }
}
