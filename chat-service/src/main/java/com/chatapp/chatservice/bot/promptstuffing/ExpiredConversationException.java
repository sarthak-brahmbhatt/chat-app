package com.chatapp.chatservice.bot.promptstuffing;

/**
 * The stored {@code previous_response_id} is no longer resolvable by OpenAI, so
 * this turn could not chain onto the conversation it belongs to (CLAUDE.md 3.9).
 *
 * <p>Multi-turn memory in Version 1 is entirely borrowed: nothing about the
 * conversation is stored here except one id, and the API is what actually holds
 * the prior turns. That retention is <b>not indefinite</b> — a response id ages
 * out server-side, and a conversation resumed days later will present an id that
 * no longer exists. Nothing about this is exceptional; it is the expected end of
 * every idle conversation, which is why it is a distinct type rather than one
 * more way for a turn to fail.
 *
 * <p>Separated from a plain {@link BotBrainException} because the two want
 * opposite responses. A genuine failure (network, API error, refusal) should
 * apologise and change nothing, so the next turn can still chain onto the last
 * good response. This one is recoverable in place: retry once without the stale
 * id and the conversation simply starts a fresh chain. The user sees a bot that
 * has forgotten the earlier conversation — which is exactly what has happened —
 * rather than an error.
 *
 * <p>Thrown only by the implementation that can recognise it (it takes SDK
 * knowledge to classify the error); acted on by
 * {@link DoctorAssistantBotService}, which owns the conversation state and so
 * owns the decision about what to do when it goes stale.
 */
public class ExpiredConversationException extends BotBrainException {

    public ExpiredConversationException(String message, Throwable cause) {
        super(message, cause);
    }
}
