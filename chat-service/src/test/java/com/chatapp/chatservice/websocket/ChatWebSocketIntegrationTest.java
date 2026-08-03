package com.chatapp.chatservice.websocket;

import com.chatapp.chatservice.dto.IncomingChatMessage;
import com.chatapp.chatservice.dto.TickAck;
import com.chatapp.chatservice.repository.ChatMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An end-to-end test of the real WebSocket round trip: boots a real embedded
 * server (@SpringBootTest, RANDOM_PORT) and connects to it with a real
 * WebSocket client over a real socket — the same mechanics as opening a
 * connection with websocat or a browser, just driven from a test. This is
 * what actually proves the handshake, path registration (WebSocketConfig),
 * and handler wiring all work together; ChatWebSocketHandlerTest checks the
 * handler's own decision logic in isolation, but can't prove any of that
 * plumbing is correctly connected.
 *
 * jwt.secret is pinned to a fixed test-only value via @TestPropertySource,
 * rather than relying on this test happening to know (and staying in sync
 * with) application.yml's real default — tokens built in this test are
 * signed with that exact same test value.
 *
 * @EnableAutoConfiguration(exclude = ...) (added alongside build-order step
 * 8, when chat-service first gained a real MySQL dependency): this test has
 * nothing to do with the database, but without excluding JPA/DataSource
 * auto-configuration, @SpringBootTest would try to eagerly open a real
 * MySQL connection at context startup just to satisfy Hibernate/HikariCP —
 * unlike Kafka (whose consumer container starts its lifecycle without
 * blocking on an actual successful broker connection, retrying in the
 * background instead), a DataSource connection pool DOES try to establish
 * a real connection up front, so this test would otherwise silently start
 * depending on Docker's MySQL being up, for no reason relevant to what it
 * actually verifies.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class
})
@TestPropertySource(properties = "jwt.secret=test-only-integration-secret-at-least-32-bytes-long-xyz")
class ChatWebSocketIntegrationTest {

    private static final String SECRET = "test-only-integration-secret-at-least-32-bytes-long-xyz";

    // ChatMessageConsumer (a real bean in this full-context test) depends on
    // ChatMessageRepository — with JPA excluded above, nothing would
    // otherwise satisfy that dependency, so it's provided as a mock here
    // purely to let the context wire up. This test never interacts with it
    // directly; ChatMessageConsumerTest covers that class's own behavior.
    @MockBean
    private ChatMessageRepository chatMessageRepository;

    // Spring Boot always publishes the embedded server's actual bound port
    // under this property when webEnvironment = RANDOM_PORT.
    @Value("${local.server.port}")
    private int port;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private String wsUri() {
        return "ws://localhost:" + port + "/ws/chat";
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

    private String validToken() {
        return tokenFor("1");
    }

    private String expiredToken() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant past = Instant.now().minusSeconds(3600);
        return Jwts.builder()
                .subject("1")
                .claim("username", "alice")
                .issuedAt(Date.from(past.minusSeconds(60)))
                .expiration(Date.from(past))
                .signWith(key)
                .compact();
    }

    /**
     * The client-side counterpart to ChatWebSocketHandler: records every text
     * message the server sends back, and completes closeStatus once the
     * server closes the connection. BlockingQueue/CompletableFuture are used
     * instead of plain fields because the actual message/close callbacks
     * happen on a background I/O thread, not the test's own thread — the
     * test has to synchronize on them rather than just reading a field
     * immediately after sending something.
     */
    private static class RecordingClientHandler extends TextWebSocketHandler {
        final BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();
        final CompletableFuture<CloseStatus> closeStatus = new CompletableFuture<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            receivedMessages.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closeStatus.complete(status);
        }
    }

    @Test
    void afterAuth_messageToNonConnectedRecipient_senderReceivesSingleTickOverRealSocket() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        RecordingClientHandler clientHandler = new RecordingClientHandler();
        WebSocketSession clientSession = client.execute(clientHandler, wsUri()).get(5, TimeUnit.SECONDS);

        clientSession.sendMessage(new TextMessage(validToken()));
        clientSession.sendMessage(new TextMessage(
                "{\"type\":\"message\",\"messageId\":\"m-int-1\",\"recipientId\":\"nobody-connected\",\"content\":\"hello?\"}"));

        String ackJson = clientHandler.receivedMessages.poll(5, TimeUnit.SECONDS);
        TickAck ack = OBJECT_MAPPER.readValue(ackJson, TickAck.class);
        assertThat(ack).isEqualTo(TickAck.single("m-int-1"));
        // The connection should still be open — a single tick never closes it,
        // and there's no recipient to deliver to (that's fine, per this step's
        // scope: no offline queue yet).
        assertThat(clientSession.isOpen()).isTrue();

        clientSession.close();
    }

    /**
     * The two-simultaneous-real-connections scenario: opens TWO real
     * WebSocket clients against the same running server, authenticates each
     * as a different user, then has A send a message addressed to B's userId.
     * This is what actually proves ConnectionRegistry + the routing logic in
     * ChatWebSocketHandler work together across two independent live
     * connections — not just "the handler calls the right mock," but two
     * real sockets, two real sessions held by the server at once, exactly
     * the scenario this build-order step exists to prove.
     */
    @Test
    void chatMessage_toConnectedRecipient_deliversLiveAndSingleTicksSenderOverRealSockets() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();

        RecordingClientHandler senderHandler = new RecordingClientHandler();
        WebSocketSession senderSession = client.execute(senderHandler, wsUri()).get(5, TimeUnit.SECONDS);
        senderSession.sendMessage(new TextMessage(tokenFor("101")));

        RecordingClientHandler recipientHandler = new RecordingClientHandler();
        WebSocketSession recipientSession = client.execute(recipientHandler, wsUri()).get(5, TimeUnit.SECONDS);
        recipientSession.sendMessage(new TextMessage(tokenFor("202")));

        // sendMessage() returns once the CLIENT has written the auth token to
        // the socket — it says nothing about whether the SERVER has finished
        // processing it and registering user "202" in ConnectionRegistry yet.
        // There's no auth-success ack in this protocol (a deliberate choice —
        // see ChatWebSocketHandler) for the test to synchronize on instead, so
        // a short wait here is what stands in for that missing signal, purely
        // to avoid a race where the sender's message below arrives before the
        // recipient is actually registered. In real usage this race is
        // vanishingly unlikely (two genuinely separate client connections,
        // typically seconds apart, not back-to-back statements in a test).
        Thread.sleep(300);

        senderSession.sendMessage(new TextMessage(
                "{\"type\":\"message\",\"messageId\":\"m-int-2\",\"recipientId\":\"202\",\"content\":\"hey 202, it's 101\"}"));

        String ackJson = senderHandler.receivedMessages.poll(5, TimeUnit.SECONDS);
        TickAck ack = OBJECT_MAPPER.readValue(ackJson, TickAck.class);
        assertThat(ack).isEqualTo(TickAck.single("m-int-2"));

        String deliveredJson = recipientHandler.receivedMessages.poll(5, TimeUnit.SECONDS);
        IncomingChatMessage delivered = OBJECT_MAPPER.readValue(deliveredJson, IncomingChatMessage.class);
        assertThat(delivered).isEqualTo(new IncomingChatMessage("incoming_message", "m-int-2", "101", "hey 202, it's 101"));

        senderSession.close();
        recipientSession.close();
    }

    @Test
    void invalidFirstMessage_serverClosesConnectionWithPolicyViolation() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        RecordingClientHandler clientHandler = new RecordingClientHandler();
        WebSocketSession clientSession = client.execute(clientHandler, wsUri()).get(5, TimeUnit.SECONDS);

        clientSession.sendMessage(new TextMessage("definitely-not-a-jwt"));

        CloseStatus closeStatus = clientHandler.closeStatus.get(5, TimeUnit.SECONDS);
        assertThat(closeStatus.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
        assertThat(clientHandler.receivedMessages).isEmpty();
    }

    @Test
    void expiredToken_serverClosesConnectionWithPolicyViolation() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        RecordingClientHandler clientHandler = new RecordingClientHandler();
        WebSocketSession clientSession = client.execute(clientHandler, wsUri()).get(5, TimeUnit.SECONDS);

        clientSession.sendMessage(new TextMessage(expiredToken()));

        CloseStatus closeStatus = clientHandler.closeStatus.get(5, TimeUnit.SECONDS);
        assertThat(closeStatus.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }
}
