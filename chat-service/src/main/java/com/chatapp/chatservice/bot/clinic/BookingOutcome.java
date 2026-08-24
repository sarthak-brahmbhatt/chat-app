package com.chatapp.chatservice.bot.clinic;

/**
 * What came of a {@code action = BOOK} decision (CLAUDE.md 3.9 §6.4).
 *
 * <p>Carries the message that should actually be sent, because the failure cases
 * must NOT reuse the model's {@code reply_to_user}: that text was written on the
 * assumption the booking succeeded, and sending it after a failed insert tells
 * the user they have an appointment they do not have. §6.4 is explicit that the
 * model's reply is discarded entirely when validation fails and a Java-written
 * message is sent instead.
 *
 * @param booked        whether a row was actually inserted
 * @param replyToUser   the text to send — the model's on success, Java's on failure
 * @param appointmentId the inserted row's id, or null when nothing was booked
 */
public record BookingOutcome(boolean booked, String replyToUser, Long appointmentId) {

    public static BookingOutcome booked(String replyToUser, Long appointmentId) {
        return new BookingOutcome(true, replyToUser, appointmentId);
    }

    public static BookingOutcome rejected(String replyToUser) {
        return new BookingOutcome(false, replyToUser, null);
    }
}
