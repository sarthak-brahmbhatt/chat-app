package com.chatapp.chatservice.bot.clinic;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One genuinely bookable slot: a doctor, a date, a time, and the availability row
 * id that a booking would have to reference.
 *
 * <p>Produced only by {@link AvailabilityService} — i.e. only ever as the result
 * of §4's subtraction, with every filter applied. Nothing else constructs one, so
 * holding a FreeSlot means the doctor is active, the pattern row is AVAILABLE,
 * and nothing is booked against it on that date. That is the whole reason this is
 * a distinct type rather than a DoctorAvailability plus a loose LocalDate: those
 * two together say nothing about whether the slot is actually free, and would let
 * an un-subtracted pair flow into a booking.
 *
 * <p>{@code availabilityId} is what the model is shown and what it echoes back to
 * book (CLAUDE.md 3.9 §6.3). An opaque id rather than a doctor-plus-time triple
 * the model would have to reassemble correctly: matching one integer back to a
 * row is something a model does reliably, and it removes any question of what
 * "9am with Dr. Mehta" resolves to when the pattern has two adjacent slots.
 */
public record FreeSlot(
        Long availabilityId,
        Long doctorId,
        String doctorName,
        String specialty,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime) {
}
