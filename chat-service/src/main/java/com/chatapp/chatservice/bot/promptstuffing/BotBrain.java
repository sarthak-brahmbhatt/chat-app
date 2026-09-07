package com.chatapp.chatservice.bot.promptstuffing;

/**
 * The model behind the bot (CLAUDE.md 3.9 §6).
 *
 * <p>An interface with exactly one production implementation, which normally
 * would not earn its keep. It does here for two reasons. Every alternative way
 * to test the turn sequence in DoctorAssistantBotService — persistence order,
 * tick timing, validate-before-reply, token accounting, conversation chaining —
 * involves either a billable network call or mocking the SDK's own builder
 * types, and the second is a test of the SDK rather than of this code. And §9
 * flags that swapping providers (Bedrock) is real rewrite work precisely because
 * this project bypasses Spring AI; this seam is where that rewrite would land,
 * which is worth knowing even though nobody is doing it now.
 */
public interface BotBrain {

    /**
     * Whether a model can actually be called. False when no API key is
     * configured, which is a legitimate state — the stack starts, chat works
     * normally, and only the bot degrades to a fixed apology.
     */
    boolean isConfigured();

    /**
     * One turn.
     *
     * @param systemPrompt       the rules plus the whole stuffed dataset, fresh
     *                           this turn
     * @param userMessage        what the user just typed
     * @param previousResponseId the previous turn's response id, or null to
     *                           start a new conversation
     * @throws BotBrainException if no usable decision came back
     */
    BotTurn respond(String systemPrompt, String userMessage, String previousResponseId);
}
