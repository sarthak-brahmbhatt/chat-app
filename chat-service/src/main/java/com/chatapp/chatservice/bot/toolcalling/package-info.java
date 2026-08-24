/**
 * Version 2 of the appointment bot — the same clinic, reached through OpenAI
 * <b>tool calling</b> instead of prompt stuffing. <b>Not implemented yet.</b>
 *
 * <p>Everything around it is already in place: a seeded {@code BOT_TOOL} user, a
 * routing branch in {@code ChatWebSocketHandler}, and the shared
 * {@code bot.clinic} and {@code bot.conversation} packages. Only the turn logic
 * is missing.
 *
 * <p><b>What changes, and what does not.</b> The clinic layer is reused
 * unchanged — {@code AvailabilityService} still owns the availability
 * subtraction and {@code BookingService} is still the only way to write an
 * appointment, with all of its validation intact. What differs is how the model
 * reaches them. Version 1 stuffs every doctor, working-hours row and booking
 * into the prompt and asks the model to do set arithmetic over it. Version 2
 * exposes those services as tools and hands back computed answers.
 *
 * <p><b>Why that is expected to matter.</b> Version 1 measurably fails at the
 * arithmetic: it offered a slot that was in its own booked list, having noticed
 * the previous one was taken and missed the next. That is not a prompting
 * problem — {@code AvailabilityService} already computes the answer exactly, in
 * one place. A tool call is how the model gets that answer instead of the raw
 * inputs. Prompt size should also stop growing with the size of the clinic,
 * which {@code bot_token_usage} will show directly, since both bots write to it.
 *
 * <p>The two are deliberately kept side by side rather than one replacing the
 * other, so the comparison can be demonstrated on the same data rather than
 * described.
 */
package com.chatapp.chatservice.bot.toolcalling;
