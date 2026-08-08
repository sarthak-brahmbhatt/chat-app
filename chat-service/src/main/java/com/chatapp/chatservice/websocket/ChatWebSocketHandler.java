package com.chatapp.chatservice.websocket;

import com.chatapp.chatservice.dto.ChatMessageRequest;
import com.chatapp.chatservice.dto.DeliveredAck;
import com.chatapp.chatservice.dto.IncomingChatMessage;
import com.chatapp.chatservice.dto.TickAck;
import com.chatapp.chatservice.kafka.ChatMessagePublisher;
import com.chatapp.chatservice.security.JwtValidator;
import com.chatapp.chatservice.service.ChatMessageService;
import com.chatapp.chatservice.service.PendingDeliveryNotification;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The WebSocket connection lifecycle, and how this class hooks into it
 * (CLAUDE.md 3.1/3.3/3.4, build-order steps 5-6, 8-9):
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
 *      chat content. Every message after that is a JSON envelope with a
 *      `type` field (CLAUDE.md 3.1) — either a "message" (see
 *      handleChatMessage()) or a "delivered_ack" (see handleDeliveredAck()),
 *      dispatched on that field (see routeAuthenticatedMessage()).
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
    private final ChatMessagePublisher chatMessagePublisher;
    private final ChatMessageService chatMessageService;

    public ChatWebSocketHandler(
            JwtValidator jwtValidator,
            ConnectionRegistry connectionRegistry,
            ObjectMapper objectMapper,
            ChatMessagePublisher chatMessagePublisher,
            ChatMessageService chatMessageService) {
        this.jwtValidator = jwtValidator;
        this.connectionRegistry = connectionRegistry;
        this.objectMapper = objectMapper;
        this.chatMessagePublisher = chatMessagePublisher;
        this.chatMessageService = chatMessageService;
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
     *   - Already authenticated: dispatch on the envelope's `type` field
     *     (see routeAuthenticatedMessage()).
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        if (!isAuthenticated(session)) {
            authenticate(session, message.getPayload());
            return;
        }

        routeAuthenticatedMessage(session, message.getPayload());
    }

    /**
     * Every post-auth envelope carries a `type` field (CLAUDE.md 3.1) — this
     * peeks at just that field (via a generic JSON tree, not deserializing
     * into any specific record yet) to decide which specific type to parse
     * the SAME payload into next. This is a deliberately lightweight
     * alternative to Jackson's polymorphic-deserialization annotations
     * (@JsonTypeInfo etc.), proportionate to having exactly two
     * client-originated types so far.
     */
    private void routeAuthenticatedMessage(WebSocketSession session, String payload) throws IOException {
        String type;
        try {
            type = objectMapper.readTree(payload).path("type").asText();
        } catch (JsonProcessingException e) {
            log.warn("Dropping unparseable message on session {}: {}", session.getId(), e.getMessage());
            return;
        }

        switch (type) {
            case "message" -> handleChatMessage(session, payload);
            case "delivered_ack" -> handleDeliveredAck(session, payload);
            default -> log.warn("Dropping message with unrecognized type '{}' on session {}", type, session.getId());
        }
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
            notifyPendingDeliveries(userId);
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
     *   1. Single-tick the sender FIRST, before anything else at all — per
     *      CLAUDE.md 3.4, single tick means "the server received this," not
     *      "it reached the recipient" or "it's durably saved." This is the
     *      one ordering rule in this method that's non-negotiable: nothing
     *      below this line may run before it, and nothing below it may ever
     *      delay it (see ChatMessagePublisher's class comment for how the
     *      Kafka publish specifically guarantees that).
     *   2. Publish to Kafka (ChatMessagePublisher) and 3. attempt live
     *      delivery (ConnectionRegistry) are TWO INDEPENDENT things that
     *      both happen to this same message — not two steps of one
     *      pipeline. Neither depends on the other's outcome: a Kafka outage
     *      doesn't affect live delivery, and the recipient being offline
     *      doesn't affect whether the message gets published for later
     *      persistence. The order they're called in below doesn't matter
     *      for the same reason — swapping them would change nothing
     *      observable.
     *
     * Malformed input (invalid JSON, or missing messageId/recipientId) is
     * logged and dropped rather than closing the connection — unlike a bad
     * auth token, a single malformed chat message on an already-authenticated
     * session isn't a reason to tear down the whole connection.
     *
     * sentAt is minted ONCE here, not separately inside the Kafka publish and
     * the live delivery - both ChatMessagePublisher.publish (the persisted
     * ChatMessageEvent) and IncomingChatMessage (the live envelope) are given
     * this SAME Instant, so a message's persisted timestamp and its
     * live-delivered timestamp can never disagree by the few milliseconds
     * two separate Instant.now() calls would otherwise drift apart.
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
        Instant sentAt = Instant.now();

        sendSingleTickAck(session, request.messageId());
        chatMessagePublisher.publish(senderId, request, sentAt);
        deliverIfRecipientConnected(senderId, request, sentAt);
    }

    private void sendSingleTickAck(WebSocketSession session, String messageId) throws IOException {
        TickAck ack = TickAck.single(messageId);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(ack)));
    }

    private void deliverIfRecipientConnected(String senderId, ChatMessageRequest request, Instant sentAt) {
        Optional<WebSocketSession> recipientSession = connectionRegistry.find(request.recipientId());
        if (recipientSession.isEmpty()) {
            log.info("Recipient {} not connected; message {} not delivered live", request.recipientId(), request.messageId());
            return;
        }

        try {
            IncomingChatMessage incoming = IncomingChatMessage.from(senderId, request, sentAt);
            recipientSession.get().sendMessage(new TextMessage(objectMapper.writeValueAsString(incoming)));
        } catch (IOException e) {
            // The recipient's socket looked connected a moment ago but failed
            // on send (e.g. it closed in the gap between the lookup above and
            // this send). This doesn't affect the sender — their single tick
            // was already sent — so this is just logged, not propagated.
            log.warn("Failed to deliver message {} to recipient {}: {}", request.messageId(), request.recipientId(), e.getMessage());
        }
    }

    /**
     * Handles a delivered_ack (CLAUDE.md 3.1, build-order step 9) — the
     * recipient's client confirming an incoming_message actually reached it.
     * senderId comes from the client (see DeliveredAck's class comment for
     * why that's a deliberate, accepted tradeoff rather than chat-service
     * tracking its own messageId -> senderId map). recipientId is read off
     * THIS session's own auth attribute — the current session belongs to
     * whoever just received the message and is acking it.
     *
     * Two things happen here now, deliberately UNCONDITIONAL on each other:
     *   - Persisting the delivery is now ASYNC, via Kafka
     *     (ChatMessagePublisher.publishDelivered), not a direct synchronous
     *     UPDATE. It's published to the SAME topic and the SAME partition
     *     key (conversationKey(senderId, recipientId)) as the original
     *     message — Kafka only guarantees ordering within one partition,
     *     but a delivered_ack can only ever be produced after the message
     *     it acknowledges was already produced (there's nothing to ack
     *     otherwise), so same-partition ordering is enough to guarantee
     *     ChatMessageConsumer always processes the insert before this
     *     delivery event, structurally — not "usually," the way a direct
     *     UPDATE racing Kafka's own insert used to be (see
     *     ChatMessageService.markDelivered's Javadoc for what that looked
     *     like before this change).
     *   - The LIVE double-tick (sendDoubleTickIfConnected) stays entirely
     *     synchronous and immediate — this mirrors CLAUDE.md 3.4's own
     *     reasoning for why single-tick doesn't wait on Kafka either:
     *     confirming something to a live, connected party right now should
     *     never be coupled to how fast the durable write happens to land.
     *
     * If the original sender is no longer connected, the LIVE double-tick
     * is silently dropped — sendDoubleTickIfConnected's own comment covers
     * why. Since the reconnect-sweep feature (ChatWebSocketHandler.authenticate
     * -> notifyPendingDeliveries), this is no longer a permanent gap for the
     * SENDER specifically either: whenever the sender's own connection next
     * cycles for any reason, nothing about the SENDER'S side needs fixing
     * here, since the persisted delivery status this method's Kafka publish
     * writes is already correct by the time anyone reads history again.
     */
    private void handleDeliveredAck(WebSocketSession session, String payload) {
        DeliveredAck ack;
        try {
            ack = objectMapper.readValue(payload, DeliveredAck.class);
        } catch (JsonProcessingException e) {
            log.warn("Dropping unparseable delivered_ack on session {}: {}", session.getId(), e.getMessage());
            return;
        }

        if (isBlank(ack.messageId()) || isBlank(ack.senderId())) {
            log.warn("Dropping delivered_ack with missing messageId/senderId on session {}", session.getId());
            return;
        }

        String recipientId = (String) session.getAttributes().get(USER_ID_ATTRIBUTE);
        chatMessagePublisher.publishDelivered(ack.senderId(), recipientId, ack.messageId());
        sendDoubleTickIfConnected(ack.senderId(), ack.messageId());
    }

    /**
     * Sends a live double-tick to senderId if (and only if) they currently
     * have a connected session — shared by handleDeliveredAck (the live
     * per-message path) and notifyPendingDeliveries (the reconnect-sweep,
     * which can surface many messages from possibly-many different original
     * senders at once). Never throws: a missing session or a failed send is
     * logged, not propagated, since neither should ever affect the CALLING
     * session (the recipient who just acked, or the recipient who just
     * reconnected) in any way.
     */
    private void sendDoubleTickIfConnected(String senderId, String messageId) {
        Optional<WebSocketSession> senderSession = connectionRegistry.find(senderId);
        if (senderSession.isEmpty()) {
            log.info("Sender {} not connected; live double-tick for message {} not sent (delivery was still persisted)",
                    senderId, messageId);
            return;
        }

        try {
            TickAck doubleTick = TickAck.doubleTick(messageId);
            senderSession.get().sendMessage(new TextMessage(objectMapper.writeValueAsString(doubleTick)));
        } catch (IOException e) {
            // Same reasoning as deliverIfRecipientConnected's catch: the
            // sender's socket looked connected a moment ago but failed on
            // send. Logged, not propagated — this doesn't affect the
            // CALLING session at all.
            log.warn("Failed to deliver double-tick for message {} to sender {}: {}", messageId, senderId, e.getMessage());
        }
    }

    /**
     * Sweeps every message where the user who JUST authenticated on this
     * session is the recipient and delivery was never recorded, marks each
     * delivered, and live-notifies whichever original sender happens to be
     * connected right now — see ChatMessageService.sweepUndeliveredForRecipient
     * for the full reasoning (why this is needed at all, why it's safe to
     * run on every connect including ordinary first-time ones, and the
     * accepted concurrent-double-sweep tradeoff). Deliberately does not
     * re-send message CONTENT to recipientId's own session — see that same
     * Javadoc for why.
     */
    private void notifyPendingDeliveries(String recipientId) {
        List<PendingDeliveryNotification> notifications = chatMessageService.sweepUndeliveredForRecipient(recipientId);
        for (PendingDeliveryNotification notification : notifications) {
            sendDoubleTickIfConnected(notification.senderId(), notification.messageId());
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
