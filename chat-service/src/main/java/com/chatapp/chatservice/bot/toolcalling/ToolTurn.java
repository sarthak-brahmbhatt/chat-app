package com.chatapp.chatservice.bot.toolcalling;

import com.chatapp.chatservice.bot.promptstuffing.TokenUsage;

import java.util.List;

/**
 * One completed tool-calling turn (CLAUDE.md 3.10).
 *
 * <p>Version 1's turn is one API call. This one is a LOOP — the model asks for a
 * lookup, gets an answer, and may ask again before it is ready to reply. So
 * everything here is an aggregate over the whole round trip rather than a single
 * response.
 *
 * @param replyToUser  the model's final text, after the last tool call resolved
 * @param responseId   the id of the LAST response in the loop — the one the next
 *                     turn chains from. Chaining from an earlier one would drop
 *                     the tool results from the conversation's memory.
 * @param usage        tokens summed across EVERY call in the loop, not just the
 *                     last, or the comparison against Version 1 would flatter
 *                     Version 2 by counting a fraction of what it spent
 * @param model        the model that served it
 * @param invocations  every tool call made, in order — the trace
 * @param rounds       how many API calls the loop took. A turn needing many is
 *                     worth noticing: it is the cost Version 2 pays in latency
 *                     for the accuracy it buys.
 */
public record ToolTurn(
        String replyToUser,
        String responseId,
        TokenUsage usage,
        String model,
        List<ToolInvocation> invocations,
        int rounds) {
}
