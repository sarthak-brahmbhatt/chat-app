package com.chatapp.userservice.entity;

/**
 * What kind of participant a {@link User} row represents (CLAUDE.md 3.9,
 * build-order step 18).
 *
 * <p>Persisted as its NAME, not its ordinal (see {@code User.userType}) — an
 * ordinal would silently remap every existing row the moment a constant is
 * inserted anywhere but the end of this list.
 *
 * <p>AGENT (human support agent, for the transfer flow) and DOCTOR are both
 * deliberately absent. Doctors in particular are reference data in this design,
 * not participants: they never log in and never chat, so giving them a `users`
 * row would model a relationship that doesn't exist. Both are explicitly out of
 * scope for Version 1 — add them when the feature that needs them arrives, not
 * speculatively.
 */
public enum UserType {

    /** A real person with a password, who logs in and chats. The default. */
    USER,

    /**
     * The DoctorAssistant appointment bot. Exactly one row today.
     *
     * <p>It is a real `users` row rather than a virtual entity invented at the
     * API boundary, and that is the whole trick behind this feature: the
     * frontend user list, the WebSocket message envelope,
     * `messages.sender_id`/`recipient_id`, and conversation history all keep
     * working with no changes at all, because as far as every one of them is
     * concerned the bot is just another user. This column is the ONLY thing
     * that distinguishes it, and only chat-service's routing branch looks.
     */
    BOT,
    /**
     * The Version 2 appointment bot, reached through OpenAI tool calling
     * instead of prompt stuffing.
     *
     * <p>A SECOND bot user, deliberately alongside {@link #BOT} rather than
     * replacing it. Both are seeded, both appear in the user list, and both
     * answer at the same time — so the two approaches can be demonstrated side
     * by side against the same clinic data, which is the whole point of
     * building the naive one first.
     *
     * <p>They share every table. Different bot user ids produce different
     * conversation keys, so their chains, token usage and prompt logs sit in
     * the same tables without colliding — and the token cost of one can be
     * compared directly against the other.
     */
    BOT_TOOL
}
