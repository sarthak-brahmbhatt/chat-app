package com.chatapp.chatservice.bot.routing;

/**
 * What the bot wants said back, handed to ChatWebSocketHandler to persist and
 * send (CLAUDE.md 3.9 §5.3 step 8).
 *
 * <p>The id is server-generated here, unlike every other {@code messageId} in
 * this system, which is minted by the sending CLIENT so it can render an
 * optimistic bubble and correlate a later tick to it (CLAUDE.md 3.1). The bot
 * has no client to do that, so whichever component speaks for it has to — and
 * that has to be this side of the call, because the id is also written to
 * {@code bot_token_usage.message_id} to tie a reply to what it cost.
 *
 * @param messageId a UUID, same shape as a browser-generated one, so nothing
 *                  downstream can tell the difference
 * @param content   the text to send — either the model's own reply, or a
 *                  Java-written message when the turn failed or a booking was
 *                  rejected
 */
public record BotReply(String messageId, String content) {
}
