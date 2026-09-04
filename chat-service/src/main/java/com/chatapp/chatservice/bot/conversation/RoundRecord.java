package com.chatapp.chatservice.bot.conversation;

/**
 * One request/response pair with the model, captured verbatim.
 *
 * <p>A turn is not always one call. Version 2's loop makes up to five, and
 * {@code bot_prompt_log} collapses them into a single row — the prompt it shows
 * is the one built once at the start, not the several requests actually sent.
 * That row cannot answer "what did round 2 look like", which is exactly the
 * question the loop raises.
 *
 * <p>Carried out of the brain rather than persisted by it: the brains stay free
 * of repositories, and a caller that does not want the log simply drops these.
 *
 * @param roundNumber        1-based, in the order the calls were made
 * @param requestJson        the exact body sent — instructions, input, tools,
 *                           previous_response_id. Taken from the SDK's own
 *                           {@code _body()}, so it cannot drift from the wire
 * @param responseJson       the whole response, including output items and usage
 * @param previousResponseId what this round chained from, or null
 * @param responseId         what this round returned
 * @param inputTokens        THIS round's cost, not the turn's total
 * @param outputTokens       likewise
 */
public record RoundRecord(
        int roundNumber,
        String requestJson,
        String responseJson,
        String previousResponseId,
        String responseId,
        int inputTokens,
        int outputTokens) {
}
