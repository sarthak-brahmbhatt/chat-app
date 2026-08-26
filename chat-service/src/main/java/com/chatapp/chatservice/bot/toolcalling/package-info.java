/**
 * Version 2 of the appointment bot — the same clinic, reached through OpenAI
 * <b>tool calling</b> instead of prompt stuffing.
 *
 * <p>Runs ALONGSIDE Version 1, not instead of it: a seeded {@code BOT_TOOL}
 * user, its own branch in {@code ChatWebSocketHandler}, and the shared
 * {@code bot.clinic} and {@code bot.conversation} packages used unchanged. Both
 * bots answer at once against the same data, so the two approaches can be put
 * side by side rather than described.
 *
 * <p><b>What changes, and what does not.</b> The clinic layer is reused
 * unchanged — {@code AvailabilityService} still owns the availability
 * subtraction and {@code BookingService} is still the only way to write an
 * appointment, with all of its validation intact. What differs is how the model
 * reaches them. Version 1 stuffs every doctor, working-hours row and booking
 * into the prompt and asks the model to do set arithmetic over it. Version 2
 * exposes those services as tools and hands back computed answers.
 *
 * <p><b>What it bought, measured.</b> Average system prompt 14,915 -> 3,341
 * characters; average input 5,536 -> 2,768 tokens per turn, on the same
 * conversations against the same clinic. Version 1's floor rises with every
 * doctor added, because the whole clinic is in every prompt; this one's does
 * not. Both bots write to {@code bot_token_usage}, so that is one query.
 *
 * <p>Two of Version 1's live failures also become unreachable rather than
 * merely less likely. It offered a slot that was in its own booked list —
 * {@code get_available_slots} returns what {@code AvailabilityService} already
 * computed, so there is no arithmetic left to get wrong. And it told two
 * patients each other's appointments — {@code get_my_appointments} takes no
 * patient argument at all, so the question cannot be expressed.
 *
 * <p>The cost is latency: a turn needing three rounds is three sequential API
 * calls, 10-18s against Version 1's 3-6s.
 *
 * <p>The two are deliberately kept side by side rather than one replacing the
 * other, so the comparison can be demonstrated on the same data rather than
 * described.
 */
package com.chatapp.chatservice.bot.toolcalling;
