package com.chatapp.chatservice.bot.toolcalling;

/**
 * Receives a turn's progress as it happens, so the user sees words appear
 * instead of watching a tick for eight seconds (CLAUDE.md 3.10).
 *
 * <p>An interface rather than a {@code Consumer<String>} because there are two
 * genuinely different kinds of progress, and a chat window should show them
 * differently. Text is the answer being written. A status is the bot going away
 * to look something up — which, in a tool-calling turn, is most of the wait.
 *
 * <p>Implemented by ChatWebSocketHandler, which turns each callback into a
 * WebSocket frame. Deliberately knows nothing about sockets itself: the brain
 * calls this while streaming, and where the words end up is the caller's
 * business.
 *
 * <p><b>Called on the WebSocket's own inbound thread</b>, synchronously, as
 * chunks arrive from OpenAI. So an implementation must be quick and must not
 * throw — a listener that blows up mid-stream would abandon a turn that was
 * otherwise fine.
 */
public interface BotStreamListener {

    /**
     * The bot is about to do something that takes a moment — already phrased
     * for a patient to read ("Checking availability…"), not a tool name.
     */
    void onStatus(String humanReadableStatus);

    /** The next chunk of the reply. Chunks concatenate to the final text. */
    void onTextDelta(String delta);

    /**
     * For callers that do not care — the non-streaming path, and tests.
     * Streaming is presentation; a turn is correct with or without it.
     */
    BotStreamListener NOOP = new BotStreamListener() {
        @Override
        public void onStatus(String humanReadableStatus) {
        }

        @Override
        public void onTextDelta(String delta) {
        }
    };
}
