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
 * re-authentication without first having to decode the JWT itself just to
 * find its own expiry.
 *
 * refreshToken (build-order step 10): the longer-lived token to present to
 * POST /refresh once the access token above expires — see
 * RefreshTokenService for the full design.
 */
public class LoginResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    private long expiresInSeconds;

    public LoginResponse(String accessToken, String refreshToken, long expiresInSeconds) {
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
