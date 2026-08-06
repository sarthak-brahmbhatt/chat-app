package com.chatapp.chatservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * chat-service's FIRST WebMvcConfig — before ConversationController, this
 * service had no plain HTTP endpoints at all (only /ws/chat, whose CORS is
 * handled entirely separately by WebSocketConfig.setAllowedOrigins, a
 * WebSocket-specific handshake-origin check, NOT Spring MVC's CORS support
 * - see that class's comment for why those are two unrelated mechanisms).
 * This is the HTTP-fetch/XHR equivalent, needed now that
 * ConversationController exists: without it, a browser's preflight to
 * GET /conversations/{otherUserId}/messages would be refused and Angular
 * would never even send the real request, exactly the failure mode
 * user-service's own WebMvcConfig.addCorsMappings comment describes in
 * detail.
 *
 * Same three origins, same reasoning, as user-service's CORS config and
 * chat-service's own WebSocketConfig — repeated here rather than shared,
 * since HTTP CORS and the WebSocket origin check are configured through
 * completely different Spring APIs with no common place to define an
 * origin list once for both.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/conversations/**")
                .allowedOrigins(
                        "http://localhost:4200",
                        "https://d3ky4h6sqgh0ie.cloudfront.net",
                        "https://sarthak-chat-app.beer")
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*");
    }
}
