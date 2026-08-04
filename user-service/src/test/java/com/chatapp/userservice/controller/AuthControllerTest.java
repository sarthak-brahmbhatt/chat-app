package com.chatapp.userservice.controller;

import com.chatapp.userservice.dto.LoginRequest;
import com.chatapp.userservice.dto.LoginResponse;
import com.chatapp.userservice.dto.RefreshRequest;
import com.chatapp.userservice.dto.RefreshResponse;
import com.chatapp.userservice.exception.InvalidCredentialsException;
import com.chatapp.userservice.exception.InvalidRefreshTokenException;
import com.chatapp.userservice.security.JwtService;
import com.chatapp.userservice.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest for AuthController, same shape as UserControllerTest: only the
 * web layer boots, AuthService is a Mockito mock, no real database/JWT
 * signing involved. This checks "does the controller produce the right HTTP
 * response given what AuthService returns/throws" — NOT whether the
 * credentials check itself is correct (that's AuthServiceTest's job) and NOT
 * whether the returned token is a genuine, correctly-signed JWT (also
 * AuthServiceTest, since a mock here can only hand back a fake string).
 *
 * The wrong-password and nonexistent-username tests both mock AuthService to
 * throw the exact same InvalidCredentialsException, then assert the exact
 * same 401 response shape for both — which is really confirming the
 * controller has no special-case branching for either scenario. It can't:
 * AuthService only ever gives it one exception type to react to.
 */
@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    // Not used by any test here — /login is excluded from
    // JwtAuthenticationInterceptor's enforcement (see WebMvcConfig), so it
    // never fires for these requests. But @WebMvcTest auto-detects EVERY
    // HandlerInterceptor/WebMvcConfigurer bean in the app, regardless of
    // which specific @WebMvcTest(...) controller class this slice targets —
    // so JwtAuthenticationInterceptor still gets constructed as part of this
    // test's Spring context, and its constructor needs a JwtService bean to
    // exist. Without this @MockBean, the whole context fails to start with
    // NoSuchBeanDefinitionException, even though nothing in this file
    // actually calls JwtService directly.
    @MockBean
    private JwtService jwtService;

    private String loginRequestJson(String username, String password) throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return objectMapper.writeValueAsString(request);
    }

    @Test
    void login_withValidCredentials_returns200WithAccessAndRefreshToken() throws Exception {
        LoginResponse response = new LoginResponse("fake.jwt.token", "fake.refresh.token", 900);
        when(authService.login(any(LoginRequest.class))).thenReturn(response);

        mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginRequestJson("alice", "correct-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("fake.jwt.token"))
                .andExpect(jsonPath("$.refreshToken").value("fake.refresh.token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900));
    }

    @Test
    void login_withWrongPassword_returns401WithGenericMessage() throws Exception {
        when(authService.login(any(LoginRequest.class))).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginRequestJson("alice", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    void login_withNonExistentUsername_returns401WithIdenticalBodyToWrongPassword() throws Exception {
        when(authService.login(any(LoginRequest.class))).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginRequestJson("no-such-user", "whatever")))
                .andExpect(status().isUnauthorized())
                // Same status, same message field, same value as the
                // wrong-password test above — verifying there is no way for a
                // client to tell the two cases apart from the response alone.
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    private String refreshRequestJson(String refreshToken) throws Exception {
        RefreshRequest request = new RefreshRequest();
        request.setRefreshToken(refreshToken);
        return objectMapper.writeValueAsString(request);
    }

    @Test
    void refresh_withValidToken_returns200WithNewTokenPair() throws Exception {
        RefreshResponse response = new RefreshResponse("new.access.token", "new.refresh.token", 900);
        when(authService.refresh("old.refresh.token")).thenReturn(response);

        mockMvc.perform(post("/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshRequestJson("old.refresh.token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new.access.token"))
                .andExpect(jsonPath("$.refreshToken").value("new.refresh.token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900));
    }

    @Test
    void refresh_withInvalidToken_returns401WithGenericMessage() throws Exception {
        // Covers both "not found/expired" and "reuse detected" from the
        // controller's point of view — AuthService/RefreshTokenService only
        // ever hand this controller ONE exception type for every such case
        // (see InvalidRefreshTokenException), so there's nothing here that
        // could leak which one actually happened.
        when(authService.refresh("stale.refresh.token")).thenThrow(new InvalidRefreshTokenException());

        mockMvc.perform(post("/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshRequestJson("stale.refresh.token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid refresh token"));
    }

    @Test
    void refresh_withBlankToken_returns400() throws Exception {
        mockMvc.perform(post("/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshRequestJson("")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("refreshToken is required"));
    }
}
