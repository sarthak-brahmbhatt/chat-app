package com.chatapp.chatservice.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory map of currently-connected, authenticated users: userId (the
 * JWT "sub" claim) -> that user's live WebSocketSession. This is what lets
 * ChatWebSocketHandler route a message from one connected user to another.
 *
 * SINGLE-INSTANCE CAVEAT — read this before touching this class: this map
 * only works because chat-service is deliberately run as a single instance
 * right now (CLAUDE.md 3.6). A live WebSocket connection physically exists
 * only in the memory of whichever instance accepted it. If user A's
 * connection is on instance 1 and user B's is on instance 2, instance 1's
 * copy of this map has no entry for B at all — there's no way to route
 * between instances with a plain in-memory Map. CLAUDE.md 3.6 already flags
 * exactly this: once multiple chat-service instances are needed, a SHARED
 * registry (Redis) plus a pub/sub mechanism becomes necessary to route a
 * live message to whichever instance actually holds the recipient's
 * connection. This class is the thing that gets REPLACED (or backed) by that
 * Redis registry at that point — not extended in place, since the
 * cross-instance routing problem is a different shape of problem than "look
 * up a value in a local map."
 *
 * ConcurrentHashMap, not a plain HashMap: WebSocket callbacks
 * (afterConnectionEstablished / handleTextMessage / afterConnectionClosed)
 * run on whichever I/O thread handled that particular event, so multiple
 * connections' callbacks can call into this registry concurrently — a plain
 * HashMap isn't safe under concurrent modification.
 */
@Component
public class ConnectionRegistry {

    private final Map<String, WebSocketSession> sessionsByUserId = new ConcurrentHashMap<>();

    public void register(String userId, WebSocketSession session) {
        sessionsByUserId.put(userId, session);
    }

    /**
     * Removes userId's entry ONLY if it currently points at THIS EXACT
     * session — not an unconditional removeByKey. This guards a real race:
     * if a user reconnects (a new session authenticates and overwrites the
     * map entry) before their OLD session's afterConnectionClosed callback
     * has fired yet, that late callback must not evict the NEW session's
     * entry out from under it. Map.remove(key, value) is an atomic
     * compare-and-remove for exactly this reason — it only removes when the
     * currently-mapped value equals (here, is reference-equal to, since
     * WebSocketSession doesn't override equals()) the session passed in.
     */
    public void remove(String userId, WebSocketSession session) {
        sessionsByUserId.remove(userId, session);
    }

    public Optional<WebSocketSession> find(String userId) {
        return Optional.ofNullable(sessionsByUserId.get(userId));
    }
}
