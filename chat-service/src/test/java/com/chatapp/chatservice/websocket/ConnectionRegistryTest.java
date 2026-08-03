package com.chatapp.chatservice.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A focused unit test for ConnectionRegistry's own behavior — most of its
 * register/find happy path is already exercised indirectly through
 * ChatWebSocketHandlerTest, but the conditional-remove race guard (see
 * ConnectionRegistry.remove's class comment) is subtle enough to deserve a
 * direct test of its own, rather than relying on it being incidentally
 * proven by handler-level tests.
 */
class ConnectionRegistryTest {

    private final ConnectionRegistry registry = new ConnectionRegistry();

    @Test
    void find_withNoRegisteredSession_returnsEmpty() {
        assertThat(registry.find("no-such-user")).isEmpty();
    }

    @Test
    void register_thenFind_returnsTheSameSession() {
        WebSocketSession session = mock(WebSocketSession.class);

        registry.register("42", session);

        assertThat(registry.find("42")).contains(session);
    }

    @Test
    void remove_withMatchingSession_removesEntry() {
        WebSocketSession session = mock(WebSocketSession.class);
        registry.register("42", session);

        registry.remove("42", session);

        assertThat(registry.find("42")).isEmpty();
    }

    @Test
    void remove_withStaleSession_doesNotEvictTheCurrentlyRegisteredOne() {
        // Simulates the reconnect race: user "42" was on oldSession, has
        // already reconnected on newSession (overwriting the map entry), and
        // THEN oldSession's late afterConnectionClosed callback tries to
        // remove itself. That must not evict newSession's still-current entry.
        WebSocketSession oldSession = mock(WebSocketSession.class);
        WebSocketSession newSession = mock(WebSocketSession.class);
        registry.register("42", oldSession);
        registry.register("42", newSession);

        registry.remove("42", oldSession);

        assertThat(registry.find("42")).contains(newSession);
    }
}
