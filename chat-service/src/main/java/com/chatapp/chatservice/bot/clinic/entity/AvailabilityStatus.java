package com.chatapp.chatservice.bot.clinic.entity;

/**
 * Whether a slot in a doctor's recurring weekly pattern is open for booking
 * (CLAUDE.md 3.9 §4).
 */
public enum AvailabilityStatus {

    /** Bookable, subject to not already being taken on the specific date. */
    AVAILABLE,

    /**
     * A soft delete meaning "no NEW bookings from this point forward" — never
     * "cancel what is already booked". Appointments already sitting against a
     * BLOCKED row are honoured: the doctor shows up. Rows are never physically
     * removed, because historical appointments still point at them.
     */
    BLOCKED
}
