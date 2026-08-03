package com.chatapp.chatservice.websocket;

import com.chatapp.chatservice.dto.ChatMessageRequest;
import com.chatapp.chatservice.dto.IncomingChatMessage;
import com.chatapp.chatservice.dto.TickAck;
import com.chatapp.chatservice.security.JwtValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Optional;

/**
 * The WebSocket connection lifecycle, and how this class hooks into it
 * (CLAUDE.md 3.1/3.3/3.4, build-order steps 5-6):
 *
 *   1. HANDSHAKE — a client opens a WebSocket by sending a normal HTTP GET
 *      request with an "Upgrade: websocket" header. Spring's WebSocket
 *      support (wired in WebSocketConfig) intercepts this at the configured
 *      path (/ws/chat) and, if accepted, upgrades the same TCP connection
 *      from HTTP to the WebSocket protocol. This handshake happens BEFORE
 *      any of this class's methods run — there's no hook here for it, and
 *      per CLAUDE.md 3.3 there's nothing to authenticate yet anyway (a
 *      browser can't attach a custom Authorization header to this request).
 *   2. afterConnectionEstablished — fires once, right after the handshake
 *      completes and the socket is open. The connection exists but is NOT
 *      authenticated yet.
 *   3. handleTextMessage — fires once per text frame the client sends, for
 *      as long as the session stays open. The FIRST message received on a
 *      session is always treated as the auth token (see authenticate()), not
 *      chat content. Every message after that is a chat-message envelope
 *      (see handleChatMessage()) — parsed, single-tick acknowledged back to
 *      the sender immediately, then routed live to the recipient if they're
 *      currently connected.
 *   4. afterConnectionClosed — fires once, whenever the session ends, for
 *      any reason (client disconnected, this handler closed it, network
 *      dropped). This is also where the session gets removed from
 *      ConnectionRegistry, so a disconnected user stops being routable.
 *
 * This is a raw Spring WebSocketHandler (TextWebSocketHandler), registered
 * without STOMP (@EnableWebSocketMessageBroker) — see WebSocketConfig for why
 * that distinction matters here.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    // The key used in session.getAttributes() (a per-session Map<String,Object>
    // Spring provides on every WebSocketSession specifically for handler-owned
    // state like this) to mark a session as authenticated. Its mere PRESENCE is
    // the authentication flag — no separate boolean needed. The value stored is
    // the JWT's subject claim (the user's id) — also the same id used as the
    // key in ConnectionRegistry, so a session's own attribute and its registry
    // entry always agree on "who is this."
    private static final String USER_ID_ATTRIBUTE = "userId";

    private final JwtValidator jwtValidator;
    private final ConnectionRegistry connectionRegistry;
    private final ObjectMapper objectMapper;

    public ChatWebSocketHandler(JwtValidator jwtValidator, ConnectionRegistry connectionRegistry, ObjectMapper objectMapper) {
        this.jwtValidator = jwtValidator;
        this.connectionRegistry = connectionRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket connection opened: {} (awaiting auth message)", session.getId());
    }

    /**
     * Branches on whether this session has authenticated yet:
     *   - Not yet authenticated: this message MUST be the client's JWT.
     *     Valid -> mark the session authenticated, register it in
     *     ConnectionRegistry, and stop; this message was auth, not chat
     *     content, so nothing is echoed for it. Invalid -> close the
     *     connection; no further messages on this session are ever processed.
     *   - Already authenticated: parse the message as a chat-message envelope
     *     and route it (see handleChatMessage()).
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        if (!isAuthenticated(session)) {
            authenticate(session, message.getPayload());
            return;
        }

        handleChatMessage(session, message.getPayload());
    }

    private boolean isAuthenticated(WebSocketSession session) {
        return session.getAttributes().containsKey(USER_ID_ATTRIBUTE);
    }

    private void authenticate(WebSocketSession session, String token) throws IOException {
        try {
            Claims claims = jwtValidator.validate(token);
            String userId = claims.getSubject();
            session.getAttributes().put(USER_ID_ATTRIBUTE, userId);
            connectionRegistry.register(userId, session);
            log.info("WebSocket connection {} authenticated as user {}", session.getId(), userId);
        } catch (JwtException | IllegalArgumentException e) {
            // JwtException's subtypes cover expired/malformed/bad-signature
            // tokens; IllegalArgumentException covers a blank/empty string —
            // same catch shape as user-service's JwtAuthenticationInterceptor,
            // for the same reason: the caller only needs "this token isn't
            // usable," not which specific way it failed.
            log.info("WebSocket connection {} rejected: invalid auth message ({})", session.getId(), e.getMessage());
            // 1008 (Policy Violation) is the standard WebSocket close code for
            // "you violated a requirement of this endpoint's protocol." There's
            // no WebSocket-level equivalent of HTTP's 401 — RFC 6455 defines a
            // much smaller, generic set of close codes — so 1008 is the
            // conventional choice for authentication failures specifically.
            // withReason()'s text is capped at 123 UTF-8 bytes by the spec
            // (RFC 6455 section 7.4); this message is well within that.
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Invalid or missing authentication token"));
        }
    }

    /**
     * Parses an authenticated session's message as a ChatMessageRequest
     * (CLAUDE.md 3.1) and routes it:
     *   1. Single-tick the sender FIRST, before attempting delivery at all —
     *      per CLAUDE.md 3.4, single tick means "the server received this,"
     *      not "it reached the recipient." Whether delivery below succeeds,
     *      fails, or never finds a connected recipient doesn't change the
     *      fact that the server already has the message — the ack reflects
     *      that fact, not the delivery outcome.
     *   2. Look up the recipient in ConnectionRegistry. If they're currently
     *      connected (to THIS instance — see ConnectionRegistry's class
     *      comment for why that qualifier matters), deliver live. If not,
     *      that's expected at this build step (no offline queue yet) — not
     *      an error, nothing more happens.
     *
     * Malformed input (invalid JSON, or missing messageId/recipientId) is
     * logged and dropped rather than closing the connection — unlike a bad
     * auth token, a single malformed chat message on an already-authenticated
     * session isn't a reason to tear down the whole connection.
     */
    private void handleChatMessage(WebSocketSession session, String payload) throws IOException {
        ChatMessageRequest request;
        try {
            request = objectMapper.readValue(payload, ChatMessageRequest.class);
        } catch (JsonProcessingException e) {
            log.warn("Dropping unparseable chat message on session {}: {}", session.getId(), e.getMessage());
            return;
        }

        if (isBlank(request.messageId()) || isBlank(request.recipientId())) {
            log.warn("Dropping chat message with missing messageId/recipientId on session {}", session.getId());
            return;
        }

        String senderId = (String) session.getAttributes().get(USER_ID_ATTRIBUTE);

        sendSingleTickAck(session, request.messageId());
        deliverIfRecipientConnected(senderId, request);
    }

    private void sendSingleTickAck(WebSocketSession session, String messageId) throws IOException {
        TickAck ack = TickAck.single(messageId);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(ack)));
    }

    private void deliverIfRecipientConnected(String senderId, ChatMessageRequest request) {
        Optional<WebSocketSession> recipientSession = connectionRegistry.find(request.recipientId());
        if (recipientSession.isEmpty()) {
            log.info("Recipient {} not connected; message {} not delivered live", request.recipientId(), request.messageId());
            return;
        }

        try {
            IncomingChatMessage incoming = IncomingChatMessage.from(senderId, request);
            recipientSession.get().sendMessage(new TextMessage(objectMapper.writeValueAsString(incoming)));
        } catch (IOException e) {
            // The recipient's socket looked connected a moment ago but failed
            // on send (e.g. it closed in the gap between the lookup above and
            // this send). This doesn't affect the sender — their single tick
            // was already sent — so this is just logged, not propagated.
            log.warn("Failed to deliver message {} to recipient {}: {}", request.messageId(), request.recipientId(), e.getMessage());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Removes this session from ConnectionRegistry (if it ever authenticated
     * — a session closed before completing auth was never registered, so
     * there's nothing to remove) so a disconnected user stops being routable.
     * Fires whenever the session ends, for any reason: client disconnected,
     * this handler closed it, or the network dropped.
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object userId = session.getAttributes().get(USER_ID_ATTRIBUTE);
        if (userId != null) {
            connectionRegistry.remove((String) userId, session);
        }
        log.info("WebSocket connection closed: {} ({})", session.getId(), status);
    }
}
