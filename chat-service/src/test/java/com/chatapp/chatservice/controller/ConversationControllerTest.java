package com.chatapp.chatservice.controller;

import com.chatapp.chatservice.dto.ConversationHistoryResponse;
import com.chatapp.chatservice.dto.ConversationMessageResponse;
import com.chatapp.chatservice.security.JwtValidator;
import com.chatapp.chatservice.service.ChatMessageService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A @WebMvcTest for ConversationController, same shape as UserControllerTest
 * / AuthControllerTest: only the web layer boots, ChatMessageService is a
 * Mockito mock (@MockBean) — these tests check "does the controller produce
 * the right HTTP response," not whether the query/persistence logic behind
 * it is correct (that's ChatMessageServiceTest's job, and the real query's
 * own correctness is a dual-mode manual-verification concern — see that
 * class's comment for the full reasoning this project already established).
 *
 * JwtValidator is a REAL bean here (via the nested TestConfig below), not
 * mocked — same reasoning as ChatWebSocketHandlerTest and user-service's
 * JwtAuthenticationInterceptorTest: this needs to exercise genuine
 * signature/expiry validation, including a real expired token, not a mock
 * that just returns whatever a test tells it to. @WebMvcTest only
 * auto-detects controller/advice-shaped beans, not plain @Components like
 * JwtValidator — TestConfig is what makes a real instance of it available
 * for ConversationController's constructor to receive.
 */
@WebMvcTest(ConversationController.class)
class ConversationControllerTest {

    private static final String SECRET = "test-only-jwt-signing-secret-at-least-32-bytes-long-xyz";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatMessageService chatMessageService;

    @TestConfiguration
    static class TestConfig {
        @Bean
        JwtValidator jwtValidator() {
            return new JwtValidator(SECRET);
        }
    }

    private String tokenFor(String userId) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId)
                .claim("username", "user-" + userId)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(900)))
                .signWith(key)
                .compact();
    }

    private String expiredToken() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant past = Instant.now().minusSeconds(3600);
        return Jwts.builder()
                .subject("42")
                .claim("username", "alice")
                .issuedAt(Date.from(past.minusSeconds(60)))
                .expiration(Date.from(past))
                .signWith(key)
                .compact();
    }

    @Test
    void getConversationHistory_withValidTokenAndMessages_returns200WithHistory() throws Exception {
        ConversationMessageResponse msg = new ConversationMessageResponse(
                "m-1", "42", "99", "hi there", Instant.parse("2026-01-01T10:00:00Z"), true);
        when(chatMessageService.getConversationHistory("42", "99", null))
                .thenReturn(new ConversationHistoryResponse(List.of(msg), "1 message(s) found.", false));

        mockMvc.perform(get("/conversations/99/messages")
                        .header("Authorization", "Bearer " + tokenFor("42")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("1 message(s) found."))
                .andExpect(jsonPath("$.messages[0].messageId").value("m-1"))
                .andExpect(jsonPath("$.messages[0].senderId").value("42"))
                .andExpect(jsonPath("$.messages[0].recipientId").value("99"))
                .andExpect(jsonPath("$.messages[0].content").value("hi there"))
                .andExpect(jsonPath("$.messages[0].delivered").value(true))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void getConversationHistory_withMissingAuthorizationHeader_returns401() throws Exception {
        mockMvc.perform(get("/conversations/99/messages"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Missing or malformed Authorization header"));
    }

    @Test
    void getConversationHistory_withMalformedAuthorizationHeader_returns401() throws Exception {
        // No "Bearer " prefix - e.g. the raw token pasted directly, a
        // common integration mistake worth its own explicit case, same as
        // JwtAuthenticationInterceptorTest's equivalent coverage.
        mockMvc.perform(get("/conversations/99/messages")
                        .header("Authorization", tokenFor("42")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Missing or malformed Authorization header"));
    }

    @Test
    void getConversationHistory_withExpiredToken_returns401() throws Exception {
        mockMvc.perform(get("/conversations/99/messages")
                        .header("Authorization", "Bearer " + expiredToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid or expired token"));
    }

    @Test
    void getConversationHistory_withGarbageToken_returns401() throws Exception {
        mockMvc.perform(get("/conversations/99/messages")
                        .header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid or expired token"));
    }

    @Test
    void getConversationHistory_newConversationWithRealPartner_returns200WithEmptyHistoryAndAppropriateMessage() throws Exception {
        when(chatMessageService.getConversationHistory("42", "99", null))
                .thenReturn(new ConversationHistoryResponse(List.of(), "No messages yet.", false));

        mockMvc.perform(get("/conversations/99/messages")
                        .header("Authorization", "Bearer " + tokenFor("42")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isEmpty())
                .andExpect(jsonPath("$.message").value("No messages yet."));
    }

    @Test
    void getConversationHistory_nonExistentConversationPartner_returnsIdenticalEmptyHistoryResponse() throws Exception {
        // Deliberately the SAME response shape/content as the "real but
        // never-messaged partner" test above, for a bogus-looking id -
        // chat-service has no way to distinguish the two (see
        // ChatMessageService.getConversationHistory's class comment for
        // the full reasoning: no users table of its own, and no
        // cross-service call to user-service to check). This test proves
        // that at the HTTP layer, not just asserts it in a comment.
        when(chatMessageService.getConversationHistory("42", "definitely-not-a-real-user-id", null))
                .thenReturn(new ConversationHistoryResponse(List.of(), "No messages yet.", false));

        mockMvc.perform(get("/conversations/definitely-not-a-real-user-id/messages")
                        .header("Authorization", "Bearer " + tokenFor("42")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isEmpty())
                .andExpect(jsonPath("$.message").value("No messages yet."));
    }

    @Test
    void getConversationHistory_usesAuthenticatedUserIdFromTokenNotFromPath() throws Exception {
        // The path only ever names the OTHER party; the caller's own id
        // comes exclusively from their token's subject claim - proven here
        // by stubbing the mock to respond only to the exact (callerId,
        // otherUserId) pair a correct implementation would pass, with
        // Mockito's default "no stub matched" behavior (returning null,
        // which the controller would NPE on) making any wrong wiring fail
        // loudly rather than silently pass.
        when(chatMessageService.getConversationHistory(eq("42"), eq("99"), isNull()))
                .thenReturn(new ConversationHistoryResponse(List.of(), "No messages yet.", false));

        mockMvc.perform(get("/conversations/99/messages")
                        .header("Authorization", "Bearer " + tokenFor("42")))
                .andExpect(status().isOk());
    }

    @Test
    void getConversationHistory_withBeforeParam_parsesAndPassesInstantToService() throws Exception {
        when(chatMessageService.getConversationHistory(eq("42"), eq("99"), eq(Instant.parse("2026-01-01T10:00:00Z"))))
                .thenReturn(new ConversationHistoryResponse(List.of(), "No messages yet.", false));

        mockMvc.perform(get("/conversations/99/messages")
                        .param("before", "2026-01-01T10:00:00Z")
                        .header("Authorization", "Bearer " + tokenFor("42")))
                .andExpect(status().isOk());
    }

    @Test
    void getConversationHistory_withMalformedBeforeParam_returns400() throws Exception {
        mockMvc.perform(get("/conversations/99/messages")
                        .param("before", "not-a-timestamp")
                        .header("Authorization", "Bearer " + tokenFor("42")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid 'before' timestamp"));
    }

    @Test
    void getConversationHistory_withFullPage_returns200WithHasMoreTrue() throws Exception {
        ConversationMessageResponse msg = new ConversationMessageResponse(
                "m-1", "42", "99", "hi there", Instant.parse("2026-01-01T10:00:00Z"), true);
        when(chatMessageService.getConversationHistory("42", "99", null))
                .thenReturn(new ConversationHistoryResponse(List.of(msg), "1 message(s) found.", true));

        mockMvc.perform(get("/conversations/99/messages")
                        .header("Authorization", "Bearer " + tokenFor("42")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore").value(true));
    }
}
