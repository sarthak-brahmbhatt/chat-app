package com.chatapp.chatservice.bot;

/**
 * One completed model call: what it decided, what it cost, and the id that lets
 * the NEXT call remember it (CLAUDE.md 3.9 §5.3).
 *
 * @param decision   the structured reply — see BotDecision
 * @param responseId OpenAI's id for this response, stored as
 *                   {@code bot_conversation_state.last_response_id} and sent
 *                   back as {@code previous_response_id} next turn. Without it
 *                   the conversation restarts from nothing every message.
 * @param usage      token counts, destined for {@code bot_token_usage}
 * @param model      the model that actually served the call, recorded per row so
 *                   a config change does not silently pool two models' numbers
 */
public record BotTurn(BotDecision decision, String responseId, TokenUsage usage, String model) {
}
