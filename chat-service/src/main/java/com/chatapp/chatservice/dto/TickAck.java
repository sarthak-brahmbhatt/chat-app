package com.chatapp.chatservice.dto;

/**
 * The server → sender envelope reporting a tick state (CLAUDE.md 3.1/3.4):
 * {"type":"ack","tick":"single","messageId":"..."} or
 * {"type":"ack","tick":"double","messageId":"..."}
 *
 * One shape with a `tick` field, rather than a separate message type per
 * tick state — single and double tick are two states of the same concept
 * (has the message been received vs. delivered), not two unrelated events.
 *
 * The static factories exist so every call site produces the "type":"ack"
 * literal the same way, rather than each caller constructing the record
 * directly and risking a typo'd type/tick string that wouldn't be caught
 * until a client failed to recognize the message.
 *
 * doubleTick(...), not double(...): "double" is a reserved Java keyword
 * (a primitive type), so it can't be used as a method name at all.
 */
public record TickAck(String type, String tick, String messageId) {

    public static TickAck single(String messageId) {
        return new TickAck("ack", "single", messageId);
    }

    public static TickAck doubleTick(String messageId) {
        return new TickAck("ack", "double", messageId);
    }
}
