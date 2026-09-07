package com.chatapp.chatservice.bot.promptstuffing;

/**
 * The only two things a model turn is allowed to ask for (CLAUDE.md 3.9 §6.3).
 *
 * <p>An enum, not a free string, so the generated JSON schema carries
 * {@code enum: ["BOOK","NONE"]} and the API cannot decode anything else — there
 * is no third value to write a defensive branch for.
 *
 * <p>Cancellation is absent because it is out of scope for Version 1. Adding a
 * CANCEL constant here would be the smallest part of that feature and the most
 * misleading: it would imply a write path that does not exist, and the
 * unconditional unique constraint on appointments assumes cancelled rows never
 * appear (see Appointment).
 */
public enum BotAction {

    /**
     * Book the named slot. Only legitimate after the user has explicitly
     * confirmed a specific doctor, date and time that was already offered to
     * them — never on a first mention and never on an ambiguous reply.
     */
    BOOK,

    /**
     * Just reply. Greetings, questions, offering options, asking for
     * confirmation, and turning someone away because their specialty isn't
     * offered are all NONE.
     */
    NONE
}
