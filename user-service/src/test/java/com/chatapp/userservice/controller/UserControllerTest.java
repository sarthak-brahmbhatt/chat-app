package com.chatapp.userservice.controller;

import com.chatapp.userservice.dto.RegisterRequest;
import com.chatapp.userservice.dto.RegisterResponse;
import com.chatapp.userservice.dto.UserListResponse;
import com.chatapp.userservice.dto.UserSummary;
import com.chatapp.userservice.exception.DuplicateUsernameException;
import com.chatapp.userservice.security.JwtService;
import com.chatapp.userservice.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A "basic" test for POST /register, deliberately scoped as a @WebMvcTest:
 * it boots only the web layer (controller + GlobalExceptionHandler + Jackson
 * JSON conversion), not the whole application — no real database or Redis
 * connection is needed, which is what keeps this fast and independent of
 * whatever's running in Docker. UserService itself is replaced with a
 * Mockito mock (@MockBean), so these tests are only checking: "given what
 * UserService returns/throws, does the controller produce the right HTTP
 * response?" UserService's own logic (hashing, the duplicate check query)
 * would get its own dedicated unit test in a future pass — out of scope for
 * this "basic test" pass.
 */
@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // @MockBean puts a Mockito mock of UserService into the test's Spring
    // context in place of the real bean, so UserController gets it injected
    // exactly like in production, minus any real logic.
    @MockBean
    private UserService userService;

    // JwtAuthenticationInterceptor is auto-detected by @WebMvcTest (it's a
    // HandlerInterceptor bean) regardless of which controller this slice
    // targets, and its constructor needs a real JwtService bean to exist —
    // same reasoning as in AuthControllerTest. For the GET /users tests below,
    // this mock is also actively stubbed per test (unlike in
    // AuthControllerTest, where it's only present to satisfy wiring) since
    // /users IS covered by the interceptor.
    @MockBean
    private JwtService jwtService;

    @Test
    void register_withValidRequest_returns201AndNoPasswordField() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("alice");
        request.setPassword("s3cret-password");
        request.setFirstName("Alice");
        request.setLastName("Smith");

        RegisterResponse response = new RegisterResponse(1L, "alice", "Alice", "Smith");
        when(userService.register(any(RegisterRequest.class))).thenReturn(response);

        mockMvc.perform(post("/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.firstName").value("Alice"))
                .andExpect(jsonPath("$.lastName").value("Smith"))
                // Confirms RegisterResponse's contract: no password field in the
                // body at all, hashed or otherwise.
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void register_withMissingUsername_returns400AndDoesNotCallService() throws Exception {
        // username omitted entirely; password/firstName/lastName present.
        String requestBody = """
                {
                  "password": "s3cret-password",
                  "firstName": "Alice",
                  "lastName": "Smith"
                }
                """;

        mockMvc.perform(post("/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("username is required"));

        // @Valid fails before the controller method body runs, so the service
        // should never be reached for an invalid request.
        verifyNoInteractions(userService);
    }

    @Test
    void register_withDuplicateUsername_returns409() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("alice");
        request.setPassword("s3cret-password");
        request.setFirstName("Alice");
        request.setLastName("Smith");

        when(userService.register(any(RegisterRequest.class)))
                .thenThrow(new DuplicateUsernameException("alice"));

        mockMvc.perform(post("/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username 'alice' is already taken"));
    }

    @Test
    void listUsers_withValidToken_returns200WithUserList() throws Exception {
        when(jwtService.parseToken(anyString())).thenReturn(null);
        List<UserSummary> summaries = List.of(
                new UserSummary(1L, "alice", "Alice", "Smith", "USER"),
                new UserSummary(2L, "bob", "Bob", "Jones", "USER")
        );
        when(userService.listUsers()).thenReturn(new UserListResponse(summaries, "2 user(s) found."));

        mockMvc.perform(get("/users").header("Authorization", "Bearer some-valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("2 user(s) found."))
                .andExpect(jsonPath("$.users.length()").value(2))
                .andExpect(jsonPath("$.users[0].username").value("alice"))
                .andExpect(jsonPath("$.users[0].password").doesNotExist());
    }

    @Test
    void listUsers_withMissingToken_returns401AndNeverCallsService() throws Exception {
        // No Authorization header at all. JwtAuthenticationInterceptor should
        // reject this before UserController.listUsers() ever runs.
        mockMvc.perform(get("/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Missing or malformed Authorization header"));

        verifyNoInteractions(userService);
    }

    @Test
    void listUsers_withInvalidToken_returns401AndNeverCallsService() throws Exception {
        when(jwtService.parseToken(anyString())).thenThrow(new JwtException("token signature/expiry invalid"));

        mockMvc.perform(get("/users").header("Authorization", "Bearer some-invalid-or-expired-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid or expired token"));

        verifyNoInteractions(userService);
    }

    @Test
    void listUsers_withNoRegisteredUsers_returns200WithDescriptiveMessage() throws Exception {
        when(jwtService.parseToken(anyString())).thenReturn(null);
        when(userService.listUsers()).thenReturn(new UserListResponse(List.of(), "No registered users found."));

        mockMvc.perform(get("/users").header("Authorization", "Bearer some-valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.length()").value(0))
                .andExpect(jsonPath("$.message").value("No registered users found."));
    }
}
