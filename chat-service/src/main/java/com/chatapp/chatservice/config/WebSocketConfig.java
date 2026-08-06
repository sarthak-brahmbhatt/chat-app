package com.chatapp.chatservice.config;

import com.chatapp.chatservice.websocket.ChatWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers ChatWebSocketHandler at /ws/chat.
 *
 * @EnableWebSocket + implementing WebSocketConfigurer is Spring's RAW
 * WebSocket support — deliberately not @EnableWebSocketMessageBroker (STOMP).
 * STOMP layers its own framing and sub-protocol on top of plain WebSocket
 * (destinations, message types, a broker-relay model, its own handshake
 * negotiation), which is exactly the kind of imposed message vocabulary
 * CLAUDE.md 3.1 already ruled out — that's the same reasoning that dropped
 * XMPP in favor of a custom message/ack payload format this project owns
 * itself. Adopting STOMP here would reintroduce that imposed structure
 * through a different door.
 *
 * No SockJS fallback: SockJS exists to work around browsers/proxies that
 * can't do real WebSocket at all, which isn't a constraint here.
 *
 * setAllowedOrigins (build-order step 7, Angular frontend): this is a
 * SEPARATE mechanism from user-service's HTTP CORS config
 * (WebMvcConfig.addCorsMappings) — raw WebSocket handshakes aren't HTTP
 * fetch/XHR requests, so Spring MVC's CORS support doesn't apply to them at
 * all. Instead, WebSocketHandlerRegistration has its own origin check: during
 * the handshake, if the browser's Origin header isn't in this allow-list,
 * Spring rejects the handshake outright (before ChatWebSocketHandler's
 * afterConnectionEstablished ever runs). Scoped to exactly the Angular dev
 * server, same reasoning as user-service's CORS config — not "*".
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler) {
        this.chatWebSocketHandler = chatWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/chat")
                // Same three origins, and the same reasoning, as
                // user-service's WebMvcConfig.addCorsMappings — but enforced
                // by a completely separate mechanism (this handshake-time
                // origin check, not Spring MVC's CORS support), which is why
                // the list has to be repeated here rather than shared.
                .setAllowedOrigins(
                        "http://localhost:4200",
                        "https://d3ky4h6sqgh0ie.cloudfront.net",
                        "https://sarthak-chat-app.beer");
    }
}
