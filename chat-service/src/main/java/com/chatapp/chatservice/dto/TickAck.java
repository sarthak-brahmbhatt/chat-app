package com.chatapp.chatservice.dto;

/**
 * The server → sender envelope reporting a tick state (CLAUDE.md 3.1/3.4):
 * {"type":"ack","tick":"single","messageId":"..."}
 *
 * One shape with a `tick` field, rather than a separate message type per
 * tick state — single and double tick are two states of the same concept
 * (has the message been received vs. delivered), not two unrelated events.
 * single(...) is the only factory today; a double(...) factory is the
 * natural extension point once build-order step 9 adds double-tick.
 *
 * The static factory exists so every call site produces the "type":"ack"
 * literal the same way, rather than each caller constructing the record
 * directly and risking a typo'd type/tick string that wouldn't be caught
 * until a client failed to recognize the message.
 */
public record TickAck(String type, String tick, String messageId) {

    public static TickAck single(String messageId) {
        return new TickAck("ack", "single", messageId);
    }
}
