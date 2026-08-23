package com.chatapp.chatservice.bot;

import com.chatapp.chatservice.bot.entity.Appointment;
import com.chatapp.chatservice.bot.entity.Doctor;
import com.chatapp.chatservice.bot.entity.DoctorAvailability;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

/**
 * Renders the system prompt — the clinic's rules plus its entire dataset — for
 * one turn (CLAUDE.md 3.9 §6.1, §6.2).
 *
 * <p>The output goes in the Responses API's {@code instructions} field rather
 * than as a message in the conversation, which matters once
 * {@code previous_response_id} chaining is on: instructions apply to the current
 * call only and are not carried forward from the previous response, so each turn
 * gets FRESH clinic data and the stale copies do not pile up in the history. Put
 * the same text in a user or system MESSAGE instead and every turn's snapshot
 * would accumulate, re-billed forever and — much worse — leaving the model
 * looking at several contradictory versions of what is booked.
 *
 * <p>Everything here is rendered as compact, explicitly-labelled plain text
 * rather than JSON. It reads about the same to a model and costs meaningfully
 * fewer tokens per row, which is the axis Version 1 is trying to measure.
 */
@Component
public class BotPromptBuilder {

    private final String clinicName;

    public BotPromptBuilder(@Value("${bot.clinic-name}") String clinicName) {
        this.clinicName = clinicName;
    }

    public String systemPrompt(ClinicSnapshot snapshot) {
        return rules(snapshot) + "\n\n" + data(snapshot);
    }

    /**
     * §6.2's behavioural rules.
     *
     * <p>Several are phrased as flat prohibitions with the reason attached,
     * because the failure they prevent is the model's most natural continuation
     * rather than an unlikely edge case. Being asked for a dermatologist a clinic
     * does not employ makes inventing one far more fluent than refusing; a user
     * who says "yes, sounds good" invites booking something never actually
     * agreed on.
     */
    private String rules(ClinicSnapshot snapshot) {
        return """
                You are the appointment assistant for %s. You help patients find and book \
                appointments with the clinic's doctors. Be warm, brief and practical — this is \
                a chat window, so write two or three sentences, not paragraphs.

                THE DATA YOU ARE GIVEN IS THE ONLY DATA THAT EXISTS.
                Below you will find the clinic's complete list of doctors, their specialties, \
                their recurring weekly working hours, and every appointment already booked. \
                You cannot look anything else up. Never invent a doctor, a specialty, a time \
                slot or an availability_id that does not appear below.

                SPECIALTIES
                - The SPECIALTIES OFFERED list below is exhaustive. It is the complete set of \
                  specialties this clinic has.
                - When a patient describes symptoms, map them to the most appropriate specialty \
                  FROM THAT LIST, then offer the doctors who practise it.
                - If the specialty a patient needs is not on that list, say so plainly and \
                  directly — for example "I'm sorry, we don't have a dermatologist at %s." \
                  Do not offer a doctor from a different specialty as a substitute, do not \
                  suggest they might help anyway, and do not offer to book anything. Say what \
                  the clinic does offer only if the patient asks.

                WORKING OUT WHAT IS FREE
                - A slot is free if it appears in WEEKLY WORKING PATTERN and there is no row in \
                  ALREADY BOOKED for that same availability_id on that same date.
                - The working pattern RECURS every week. A row for MONDAY means every Monday, \
                  not one specific Monday. Use the UPCOMING DATES list to turn a weekday into a \
                  real date — do not calculate dates yourself.
                - Never offer a time in the past. Check it against today's date and the current \
                  time below.
                - When the slot a patient asks for is taken, say so and immediately offer \
                  something concrete: another time with the same doctor, or another doctor of \
                  the same specialty. Do not just report the failure.

                BOOKING
                - Confirm before booking, every time. State the doctor, the day, the date and \
                  the time back to the patient and wait for them to agree.
                - Only set action to BOOK on the turn where the patient has clearly agreed to a \
                  specific slot you already offered them. If their reply is ambiguous, or they \
                  changed something, or you are inferring what they meant — ask, and use NONE.
                - When you do set action to BOOK, copy the availability_id exactly as it \
                  appears below, and give booked_for_date as YYYY-MM-DD taken from the UPCOMING \
                  DATES list. The date's weekday must match that availability row's day.
                - Your booking is a REQUEST, not a guarantee: the clinic re-checks the slot \
                  before it is confirmed. Write reply_to_user as a confirmation anyway — if the \
                  check fails, the patient is told separately and your message is not sent.

                STAYING ON TOPIC
                - You only handle appointments at %s. If asked about anything else — medical \
                  advice, diagnoses, prescriptions, test results, billing — say that is not \
                  something you can help with and steer back to booking.
                - Never give medical advice. Mapping symptoms to the right specialty is \
                  routing, not diagnosis; do not go further than that.
                """.formatted(clinicName, clinicName, clinicName);
    }

    /** §6.1's stuffed dataset. */
    private String data(ClinicSnapshot snapshot) {
        StringBuilder out = new StringBuilder();
        Map<Long, Doctor> doctorsById = snapshot.doctorsById();

        out.append("=== TODAY ===\n")
                .append("Date: ").append(snapshot.today()).append(" (").append(snapshot.today().getDayOfWeek()).append(")\n")
                .append("Current time: ").append(snapshot.currentTime().withNano(0)).append("\n");

        // The model has no calendar any more than it has a clock: asked for "next
        // Tuesday" it will produce a confident, frequently wrong date. Listing the
        // horizon's dates against their weekdays turns that calculation into a
        // lookup, which is the difference between the weekday guard in
        // BookingService almost never firing and firing routinely.
        out.append("\n=== UPCOMING DATES (use these; do not calculate dates yourself) ===\n");
        for (LocalDate d = snapshot.today(); !d.isAfter(snapshot.horizonEnd()); d = d.plusDays(1)) {
            out.append(d).append(' ').append(d.getDayOfWeek());
            if (d.equals(snapshot.today())) {
                out.append(" (today)");
            }
            out.append('\n');
        }

        out.append("\n=== SPECIALTIES OFFERED (complete list — the clinic has no others) ===\n");
        out.append(snapshot.specialties().isEmpty()
                ? "(none)\n"
                : String.join(", ", snapshot.specialties()) + "\n");

        out.append("\n=== DOCTORS ===\n");
        if (snapshot.doctors().isEmpty()) {
            out.append("(none)\n");
        } else {
            for (Doctor d : snapshot.doctors()) {
                out.append("doctor_id=").append(d.getId())
                        .append(" | ").append(d.getName())
                        .append(" | ").append(d.getSpecialty())
                        .append('\n');
            }
        }

        out.append("\n=== WEEKLY WORKING PATTERN (recurring every week — not a booking) ===\n");
        if (snapshot.pattern().isEmpty()) {
            out.append("(none)\n");
        } else {
            for (DoctorAvailability a : snapshot.pattern()) {
                Doctor d = doctorsById.get(a.getDoctorId());
                out.append("availability_id=").append(a.getId())
                        .append(" | doctor_id=").append(a.getDoctorId())
                        .append(" | ").append(d == null ? "?" : d.getName())
                        .append(" | ").append(a.getDayOfWeek())
                        .append(" | ").append(a.getStartTime()).append('-').append(a.getEndTime())
                        .append('\n');
            }
        }

        out.append("\n=== ALREADY BOOKED (").append(snapshot.today())
                .append(" to ").append(snapshot.horizonEnd()).append(") ===\n");
        if (snapshot.bookings().isEmpty()) {
            // Stated explicitly rather than left as an empty section. An empty
            // heading reads as missing data, and a model that thinks the booking
            // list is missing hedges instead of offering the slot.
            out.append("Nothing is booked in this window — every slot in the pattern above is free.\n");
        } else {
            for (Appointment ap : snapshot.bookings()) {
                Doctor d = doctorsById.get(ap.getDoctorId());
                out.append(ap.getBookedForDate())
                        .append(" | availability_id=").append(ap.getAvailabilityId())
                        .append(" | doctor_id=").append(ap.getDoctorId())
                        .append(" | ").append(d == null ? "?" : d.getName())
                        .append(" | ").append(ap.getStartTime()).append('-').append(ap.getEndTime())
                        .append('\n');
            }
        }

        // Booking data is only loaded for the horizon, so slots beyond it cannot
        // be checked against anything. Saying so stops the model treating the
        // absence of a booking as proof a far-future slot is free.
        out.append("\nBooking data covers ").append(snapshot.today())
                .append(" to ").append(snapshot.horizonEnd())
                .append(" only. Do not offer appointments beyond ").append(snapshot.horizonEnd())
                .append(" — tell the patient you can only book up to that date for now.\n");

        return out.toString();
    }
}
