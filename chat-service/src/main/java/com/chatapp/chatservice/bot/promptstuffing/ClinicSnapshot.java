package com.chatapp.chatservice.bot.promptstuffing;

import com.chatapp.chatservice.bot.clinic.entity.Appointment;
import com.chatapp.chatservice.bot.clinic.entity.Doctor;
import com.chatapp.chatservice.bot.clinic.entity.DoctorAvailability;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Everything about the clinic that gets stuffed into one prompt
 * (CLAUDE.md 3.9 §6.1).
 *
 * <p>Read fresh on every single turn, with no caching. At five doctors that is a
 * handful of trivial queries, and the alternative — a cache — would introduce
 * the one bug this design cannot tolerate: offering a slot from a stale snapshot.
 * Freshness here is not an optimisation trade, it is the correctness property.
 *
 * <p>This whole type is Version 1's deliberate naivety made concrete. Every
 * doctor, every working-hours row and every upcoming booking is serialised into
 * the prompt on each turn regardless of what the user asked, because the model
 * has no way to look anything up. It cannot answer a question about data that
 * was not pre-injected, and the prompt grows with the clinic rather than with
 * the question. Version 2's tools fix exactly that, and the numbers in
 * {@code bot_token_usage} are what will show it needed fixing.
 *
 * @param today        the clinic's current date — the model has no clock and
 *                     cannot resolve "tomorrow" without being told
 * @param currentTime  the clinic's current wall time, so "this afternoon" and
 *                     "is there anything left today" mean something
 * @param specialties  the DISTINCT specialties actually present among active
 *                     doctors; the closed list the model is forbidden to depart
 *                     from, which is what makes turning away an unavailable
 *                     specialty work instead of the model inventing one
 * @param doctors      active doctors only
 * @param pattern      the recurring weekly working hours — NOT free time
 * @param bookings     every BOOKED appointment inside the horizon, from all
 *                     patients — purely the subtraction input for working out
 *                     what is free. Rendered WITHOUT any owner, because who
 *                     booked a slot is none of the caller's business.
 * @param myAppointments the CALLER's own bookings, and the only ones the bot may
 *                     ever describe as theirs. Split out from {@code bookings}
 *                     after a real leak in which one unattributed list let the
 *                     model hand each of two parallel patients the other's
 *                     appointments.
 * @param horizonEnd   the last date {@code bookings} covers, so the prompt can
 *                     say plainly how far ahead its booking data is trustworthy
 */
public record ClinicSnapshot(
        LocalDate today,
        LocalTime currentTime,
        List<String> specialties,
        List<Doctor> doctors,
        List<DoctorAvailability> pattern,
        List<Appointment> bookings,
        List<Appointment> myAppointments,
        LocalDate horizonEnd) {

    /**
     * Doctors by id, for resolving the {@code doctor_id} on a pattern or booking
     * row back to a name while rendering.
     *
     * <p>Built here rather than joined in SQL because both lists are already
     * fully loaded and the join would be over a five-entry table — this is the
     * one place where doing it in Java is genuinely simpler than in the query,
     * and it does not weaken the availability subtraction, which stays entirely
     * in SQL where §4 requires.
     */
    public Map<Long, Doctor> doctorsById() {
        return doctors.stream().collect(Collectors.toMap(Doctor::getId, Function.identity()));
    }

    /** True when the clinic has no active doctors at all — nothing is bookable. */
    public boolean isEmpty() {
        return doctors.isEmpty();
    }
}
