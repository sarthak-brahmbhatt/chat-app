package com.chatapp.userservice.dto;

/**
 * The success response body for POST /login (200 OK).
 *
 * tokenType is always the literal "Bearer" — the standard convention (RFC
 * 6750) for how this token must be presented on later requests
 * (Authorization: Bearer &lt;token&gt;). No endpoint enforces that yet (see
 * JwtService), but including it now costs nothing and means client code
 * doesn't have to guess or hardcode that convention itself later.
 *
 * expiresInSeconds is included so client code can proactively plan
 * re-authentication (or, once it exists, a token refresh) without first
 * having to decode the JWT itself just to find its own expiry.
 */
public class LoginResponse {

    private String accessToken;
    private String tokenType = "Bearer";
    private long expiresInSeconds;

    public LoginResponse(String accessToken, long expiresInSeconds) {
        this.accessToken = accessToken;
        this.expiresInSeconds = expiresInSeconds;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }
}
