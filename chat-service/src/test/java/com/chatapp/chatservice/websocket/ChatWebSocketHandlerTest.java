package com.chatapp.chatservice.websocket;

import com.chatapp.chatservice.dto.IncomingChatMessage;
import com.chatapp.chatservice.dto.TickAck;
import com.chatapp.chatservice.security.JwtValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A plain Mockito unit test for ChatWebSocketHandler's own decision logic —
 * no real network, no Spring context, WebSocketSession is mocked. JwtValidator
 * is real (same reasoning as JwtAuthenticationInterceptorTest in user-service):
 * this needs to exercise genuine token validation, including a truly expired
 * token, not a mock that returns whatever a test tells it to. ConnectionRegistry
 * is also real (not mocked) — it's a thin, fast wrapper over a
 * ConcurrentHashMap, so using the genuine implementation here means the
 * routing tests below exercise real register/find/remove behavior, not a
 * mocked stand-in that just returns whatever a test tells it to.
 *
 * Complements ChatWebSocketIntegrationTest, which proves the full round trip
 * (including two simultaneous real connections) over a real socket; this
 * class isolates the handler's branching logic for fast, focused coverage.
 */
@ExtendWith(MockitoExtension.class)
class ChatWebSocketHandlerTest {

    private static final String SECRET = "test-only-jwt-signing-secret-at-least-32-bytes-long-xyz";

    private ChatWebSocketHandler handler;
    private ConnectionRegistry connectionRegistry;
    private ObjectMapper objectMapper;

    @Mock
    private WebSocketSession session;

    private Map<String, Object> sessionAttributes;

    @BeforeEach
    void setUp() {
        JwtValidator jwtValidator = new JwtValidator(SECRET);
        connectionRegistry = new ConnectionRegistry();
        objectMapper = new ObjectMapper();
        handler = new ChatWebSocketHandler(jwtValidator, connectionRegistry, objectMapper);

        sessionAttributes = new HashMap<>();
        // lenient(): not every test in this class uses the shared `session`
        // field (several create their own local mocks via
        // authenticatedSession() instead), so this stub goes unused in
        // those — lenient() tells Mockito's strict-stubs checking that's
        // expected, rather than failing those tests for an "unnecessary" stub.
        lenient().when(session.getAttributes()).thenReturn(sessionAttributes);
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

    /** Creates and authenticates a mock session for the given userId, registering it in connectionRegistry. */
    private WebSocketSession authenticatedSession(String userId) throws Exception {
        WebSocketSession mockSession = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        when(mockSession.getAttributes()).thenReturn(attrs);
        handler.handleTextMessage(mockSession, new TextMessage(tokenFor(userId)));
        return mockSession;
    }

    @Test
    void handleTextMessage_firstMessageValidToken_authenticatesAndRegistersWithoutEchoOrClose() throws Exception {
        handler.handleTextMessage(session, new TextMessage(tokenFor("42")));

        assertThat(sessionAttributes).containsEntry("userId", "42");
        assertThat(connectionRegistry.find("42")).contains(session);
        verify(session, never()).sendMessage(any());
        verify(session, never()).close(any());
    }

    @Test
    void handleTextMessage_firstMessageGarbage_closesWithPolicyViolationAndStaysUnauthenticated() throws Exception {
        handler.handleTextMessage(session, new TextMessage("not-a-real-jwt"));

        assertThat(sessionAttributes).doesNotContainKey("userId");
        verify(session).close(CloseStatus.POLICY_VIOLATION.withReason("Invalid or missing authentication token"));
        verify(session, never()).sendMessage(any());
    }

    @Test
    void handleTextMessage_firstMessageExpiredToken_closesWithPolicyViolation() throws Exception {
        handler.handleTextMessage(session, new TextMessage(expiredToken()));

        assertThat(sessionAttributes).doesNotContainKey("userId");
        verify(session).close(CloseStatus.POLICY_VIOLATION.withReason("Invalid or missing authentication token"));
    }

    @Test
    void handleTextMessage_toConnectedRecipient_deliversMessageAndSingleTicksSender() throws Exception {
        WebSocketSession recipientSession = authenticatedSession("99");
        WebSocketSession senderSession = authenticatedSession("42");

        String chatMessageJson = """
                {"type":"message","messageId":"m-1","recipientId":"99","content":"hi there"}
                """;
        handler.handleTextMessage(senderSession, new TextMessage(chatMessageJson));

        ArgumentCaptor<TextMessage> senderCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(senderSession).sendMessage(senderCaptor.capture());
        TickAck ack = objectMapper.readValue(senderCaptor.getValue().getPayload(), TickAck.class);
        assertThat(ack).isEqualTo(TickAck.single("m-1"));

        ArgumentCaptor<TextMessage> recipientCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(recipientSession).sendMessage(recipientCaptor.capture());
        IncomingChatMessage delivered = objectMapper.readValue(recipientCaptor.getValue().getPayload(), IncomingChatMessage.class);
        assertThat(delivered).isEqualTo(new IncomingChatMessage("incoming_message", "m-1", "42", "hi there"));
    }

    @Test
    void handleTextMessage_toNonConnectedRecipient_stillSingleTicksSenderWithNoDelivery() throws Exception {
        WebSocketSession senderSession = authenticatedSession("42");

        String chatMessageJson = """
                {"type":"message","messageId":"m-2","recipientId":"nobody-connected","content":"anyone there?"}
                """;
        handler.handleTextMessage(senderSession, new TextMessage(chatMessageJson));

        ArgumentCaptor<TextMessage> senderCaptor = ArgumentCaptor.forClass(TextMessage.class);
        // Exactly one sendMessage call on the sender: the single-tick ack.
        // There's no second session to assert "didn't get anything" on here —
        // the absence of a recipient session mock IS the non-connected case.
        verify(senderSession).sendMessage(senderCaptor.capture());
        TickAck ack = objectMapper.readValue(senderCaptor.getValue().getPayload(), TickAck.class);
        assertThat(ack).isEqualTo(TickAck.single("m-2"));
    }

    @Test
    void handleTextMessage_malformedChatMessage_dropsWithoutAckOrException() throws Exception {
        WebSocketSession senderSession = authenticatedSession("42");

        handler.handleTextMessage(senderSession, new TextMessage("this is not json"));

        verify(senderSession, never()).sendMessage(any());
        verify(senderSession, never()).close(any());
    }

    @Test
    void afterConnectionClosed_removesSessionFromRegistry() throws Exception {
        handler.handleTextMessage(session, new TextMessage(tokenFor("42")));
        assertThat(connectionRegistry.find("42")).isPresent();

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(connectionRegistry.find("42")).isEmpty();
    }

    @Test
    void afterConnectionClosed_beforeAuthentication_doesNothingAndDoesNotThrow() {
        // A session that never sent a valid auth message never got registered
        // in the first place — closing it must not throw just because there's
        // no "userId" attribute to look up.
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        // No exception is the assertion here; nothing else to verify.
    }
}
