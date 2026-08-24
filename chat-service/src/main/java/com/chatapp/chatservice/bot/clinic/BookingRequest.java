package com.chatapp.chatservice.bot.clinic;

/**
 * A request to book one slot, in terms the clinic layer understands.
 *
 * <p>Exists so {@link BookingService} does not depend on any particular bot's
 * reply format. Version 1 builds this from its structured
 * {@code BotDecision}; the tool-calling version will build it from tool-call
 * arguments. Both want identical validation and identical writes, and neither
 * should be able to reach the database except through here.
 *
 * <p>Fields are deliberately nullable and only loosely typed, because that is
 * the honest shape of what arrives: everything here originated from a language
 * model, so "absent" and "not a real date" are ordinary cases rather than
 * programming errors. {@link BookingService} treats both as a refusal.
 *
 * @param availabilityId   the slot the model chose, or null if it named none
 * @param bookedForDate    the date as the model wrote it — a String, not a
 *                         LocalDate, because it has not been proven to be one
 *                         yet. "next Tuesday" arrives here just as readily as
 *                         "2026-08-24"
 * @param confirmationText what to tell the patient IF the booking succeeds.
 *                         Released only after a row exists; discarded entirely
 *                         on any refusal, since it was written assuming success
 */
public record BookingRequest(Long availabilityId, String bookedForDate, String confirmationText) {
}
