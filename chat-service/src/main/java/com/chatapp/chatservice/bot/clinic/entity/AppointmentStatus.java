package com.chatapp.chatservice.bot.clinic.entity;

/**
 * The lifecycle of a booked appointment (CLAUDE.md 3.9 §3.4).
 *
 * <p>Only BOOKED is ever written in Version 1 — cancellation is explicitly out of
 * scope, and nothing marks an appointment COMPLETED yet. The other three
 * constants exist because they are part of the settled schema, and because the
 * availability subtraction has to say WHICH status occupies a slot rather than
 * "any appointment row at all"; writing that predicate as {@code = BOOKED} now
 * means a later cancellation feature frees the slot by changing a status, with
 * no query to revisit.
 */
public enum AppointmentStatus {
    BOOKED,
    COMPLETED,
    CANCELLED_BY_USER,
    CANCELLED_BY_DOCTOR
}
