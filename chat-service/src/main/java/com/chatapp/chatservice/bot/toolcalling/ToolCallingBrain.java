package com.chatapp.chatservice.bot.toolcalling;

/**
 * The model behind the tool-calling bot (CLAUDE.md 3.10).
 *
 * <p>Deliberately NOT sharing an interface with Version 1's {@code BotBrain}.
 * The two have genuinely different shapes: Version 1 makes one call and returns
 * a structured decision for Java to act on; this one runs a loop, executes real
 * work partway through, and returns free text plus a trace of what it did.
 * Forcing them under one type would produce an interface that fits neither and
 * hides exactly the difference the two versions exist to show.
 */
public interface ToolCallingBrain {

    /** Whether a model can be called at all — false when no API key is set. */
    boolean isConfigured();

    /**
     * Runs one turn to completion, executing tool calls as the model asks for
     * them.
     *
     * @param systemPrompt       the (small) behavioural prompt — no clinic data
     * @param userMessage        what the patient just typed
     * @param previousResponseId the previous turn's id, or null to start fresh
     * @param executor           already scoped to the authenticated patient
     * @param listener           receives text and status as the turn happens, so
     *                           the user watches the reply being written rather
     *                           than a tick. Pass {@link BotStreamListener#NOOP}
     *                           to ignore progress; the returned turn is
     *                           identical either way, because streaming is
     *                           presentation and the final text is still
     *                           returned in full
     * @throws com.chatapp.chatservice.bot.promptstuffing.BotBrainException if no
     *         usable reply could be produced
     */
    ToolTurn respond(String systemPrompt, String userMessage, String previousResponseId,
                     ClinicToolExecutor executor, BotStreamListener listener);
}
