package com.chatapp.chatservice.controller;

import com.chatapp.chatservice.dto.ConversationHistoryResponse;
import com.chatapp.chatservice.dto.ErrorResponse;
import com.chatapp.chatservice.security.JwtValidator;
import com.chatapp.chatservice.service.ChatMessageService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * chat-service's FIRST-EVER plain REST endpoint — everything before this
 * (build-order steps 5-9) was the WebSocket connection at /ws/chat. That's
 * worth naming because it's exactly why this class validates its own
 * Authorization header inline instead of going through a
 * HandlerInterceptor, unlike user-service's near-identical-looking
 * JwtAuthenticationInterceptor + WebMvcConfig pattern (see 3.8's decision
 * — CLAUDE.md 4 — for the full reasoning, short version below).
 *
 * SHORT VERSION: an interceptor earns its keep once there's more than one
 * endpoint that needs the SAME check applied uniformly — that's why
 * user-service has one (POST /register and /login are the only EXCLUDED
 * paths; everything else, including endpoints added after the interceptor
 * existed, is protected by default). chat-service has exactly one REST
 * endpoint right now. An interceptor here would mean introducing
 * WebMvcConfig + a deny-by-default path registration + a whole new class,
 * all to save four lines of inline validation used in exactly one place —
 * more moving parts than the current surface area justifies. It also
 * would NOT unify auth handling with the WebSocket side the way it might
 * first appear to: /ws/chat's auth (JwtValidator.validate, called from
 * ChatWebSocketHandler.authenticate) happens over the FIRST MESSAGE on an
 * already-open socket, per CLAUDE.md 3.3 — that's a fundamentally
 * different Spring mechanism (HandshakeInterceptor/message handling, not
 * HandlerInterceptor) that a REST-only HandlerInterceptor could never
 * apply to regardless. So there's no unification to be had here either
 * way — only "is this worth the ceremony for one endpoint," and right now
 * it isn't. If a second REST endpoint is ever added, THAT'S the concrete
 * trigger to promote this to an interceptor, not before — same "extract
 * when there's a second real need" reasoning ChatMessageConsumer's own
 * class comment already applies to a different decision in this service.
 */
@RestController
public class ConversationController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtValidator jwtValidator;
    private final ChatMessageService chatMessageService;

    public ConversationController(JwtValidator jwtValidator, ChatMessageService chatMessageService) {
        this.jwtValidator = jwtValidator;
        this.chatMessageService = chatMessageService;
    }

    /**
     * GET /conversations/{otherUserId}/messages — the last (up to) 50
     * persisted messages between the authenticated caller and otherUserId,
     * oldest-to-newest (CLAUDE.md 4).
     *
     * Authorization is a plain "Bearer <token>" header, same scheme and
     * same JwtValidator user-service's own tokens are already verified
     * with at the /ws/chat handshake (CLAUDE.md 3.3) — this is a normal
     * HTTP request, so unlike the WebSocket handshake (which can't carry
     * custom headers at all, the entire reason CLAUDE.md 3.3 sends the
     * token as the first WS message instead), there's no reason NOT to use
     * the standard Authorization header here.
     *
     * `before` (optional query param, ISO-8601 instant) is the cursor for
     * every page after the first — see ChatMessageRepository's
     * findConversationBeforeMostRecentFirst for why this is cursor-based,
     * not offset-based. Absent means "the first (most recent) page."
     */
    @GetMapping("/conversations/{otherUserId}/messages")
    public ResponseEntity<?> getConversationHistory(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @PathVariable String otherUserId,
            @RequestParam(required = false) String before) {

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return unauthorized("Missing or malformed Authorization header");
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        Claims claims;
        try {
            claims = jwtValidator.validate(token);
        } catch (JwtException | IllegalArgumentException e) {
            // Same catch shape, and same reasoning, as
            // ChatWebSocketHandler.authenticate() and user-service's
            // JwtAuthenticationInterceptor: the caller only needs "this
            // token isn't usable," not which specific way it failed.
            return unauthorized("Invalid or expired token");
        }

        Instant beforeCursor = null;
        if (before != null) {
            try {
                beforeCursor = Instant.parse(before);
            } catch (DateTimeParseException e) {
                return ResponseEntity.badRequest().body(new ErrorResponse("Invalid 'before' timestamp"));
            }
        }

        String currentUserId = claims.getSubject();

        ConversationHistoryResponse history =
                chatMessageService.getConversationHistory(currentUserId, otherUserId, beforeCursor);
        return ResponseEntity.ok(history);
    }

    private ResponseEntity<ErrorResponse> unauthorized(String message) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(message));
    }
}
