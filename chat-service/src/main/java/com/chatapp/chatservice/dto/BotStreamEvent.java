package com.chatapp.chatservice.dto;

/**
 * Server → client, a bot reply being written (CLAUDE.md 3.1's envelope family).
 *
 * <p>Three shapes, all carrying the {@code messageId} the finished message will
 * have. That id is what ties them together: the client opens a bubble on
 * {@code bot_stream_start}, grows it on each {@code bot_stream_delta}, and then
 * REPLACES its text when the ordinary {@code incoming_message} arrives with the
 * same id.
 *
 * <p>Replacing rather than relying on the accumulated chunks is what makes this
 * safe to bolt onto the existing protocol. The final message stays
 * authoritative, exactly as before; streaming is a preview of it. A dropped
 * chunk, a client that ignores these types entirely, or a stream that fails
 * halfway all end at the same correct place, because {@code incoming_message}
 * is still sent and still persisted.
 *
 * <p>Nothing here is acknowledged and nothing here is stored. These frames are
 * presentation only — history is built from {@code messages}, which never sees
 * them.
 */
public record BotStreamEvent(String type, String messageId, String senderId, String text) {

    /** Open an empty bubble; the reply is coming. */
    public static BotStreamEvent start(String messageId, String senderId) {
        return new BotStreamEvent("bot_stream_start", messageId, senderId, null);
    }

    /** The next chunk of text to append. */
    public static BotStreamEvent delta(String messageId, String senderId, String delta) {
        return new BotStreamEvent("bot_stream_delta", messageId, senderId, delta);
    }

    /**
     * A transient "looking something up" note, shown in place of the bubble's
     * text and replaced by the next delta.
     *
     * <p>Carries most of the value for a tool-calling turn: the model produces
     * no text at all while it is calling tools, so without this the user would
     * still stare at nothing for the several seconds that actually dominate the
     * wait.
     */
    public static BotStreamEvent status(String messageId, String senderId, String status) {
        return new BotStreamEvent("bot_status", messageId, senderId, status);
    }
}
