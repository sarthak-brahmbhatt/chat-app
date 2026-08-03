package com.chatapp.chatservice.dto;

/**
 * The client → server envelope for sending a chat message (CLAUDE.md 3.1,
 * build-order step 6): {"type":"message","messageId":"...","recipientId":"...","content":"..."}
 *
 * This is a Java record, not a plain class with getters/setters like
 * user-service's DTOs — a deliberate, flagged difference, not an
 * inconsistency. user-service's DTOs are shaped by Spring MVC's
 * @RequestBody/Jackson conventions and Bean Validation (@NotBlank etc.),
 * which is most naturally expressed as mutable JavaBean-style classes. This
 * envelope is parsed manually (ObjectMapper.readValue, not @RequestBody) from
 * a raw WebSocket text frame — there's no framework convention pulling
 * toward a mutable bean here, and it's a simple immutable value: exactly what
 * records are for. Jackson (2.12+, and Spring Boot 3.3's bundled version)
 * deserializes records natively, matching JSON field names to record
 * component names.
 *
 * type is part of the documented wire shape but not dispatched on in code
 * yet — this is the only client→server message type that exists so far.
 * Add real dispatch (e.g. a switch on type) once a second type is
 * introduced (build-order step 9, once the recipient can send a double-tick
 * ack back over the same socket).
 */
public record ChatMessageRequest(String type, String messageId, String recipientId, String content) {
}
