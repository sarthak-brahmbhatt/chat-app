package com.chatapp.chatservice.bot;

import com.chatapp.chatservice.bot.entity.Appointment;
import com.chatapp.chatservice.bot.entity.Doctor;
import com.chatapp.chatservice.bot.entity.DoctorAvailability;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.stream.Collectors;

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

    /**
     * @param firstTurn whether this is the opening message of the conversation.
     *                  Passed in rather than inferred, because the model cannot
     *                  tell: with no {@code previous_response_id} it simply sees
     *                  no history, which is not the same as knowing it is turn
     *                  one — in testing it opened with the welcome sometimes and
     *                  skipped it other times. The caller knows for certain.
     */
    public String systemPrompt(ClinicSnapshot snapshot, boolean firstTurn) {
        return rules(snapshot, firstTurn) + "\n\n" + data(snapshot);
    }

    /**
     * Collapses the whitespace a text block's line continuations leave behind.
     *
     * <p>Every {@code \}-continued line in {@link #rules} keeps the two extra
     * spaces it is indented by past the block's common margin, so a rule written
     * across three source lines renders with a stray run of spaces at each join.
     * A model reads through that fine, but it is noise in the logs, it is
     * needless tokens on every single turn, and it makes any assertion about the
     * prompt text depend on where the source happened to wrap.
     *
     * <p>Only runs that FOLLOW a non-space character are collapsed, which is what
     * makes this safe: leading indentation is what distinguishes a nested list
     * item from a top-level one, and it is untouched.
     */
    private static String collapseContinuationGaps(String text) {
        return text.replaceAll("(?<=\\S) {2,}", " ");
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
    private String rules(ClinicSnapshot snapshot, boolean firstTurn) {
        String template = """
                %s

                You are the appointment assistant for %s. You help patients find and book \
                appointments with the clinic's doctors. Be warm, brief and practical — this is \
                a chat window, so write two or three sentences, not paragraphs.

                THE DATA YOU ARE GIVEN IS THE ONLY DATA THAT EXISTS.
                Below you will find the clinic's complete list of doctors, their specialties, \
                their recurring weekly working hours, and every appointment already booked. \
                You cannot look anything else up. Never invent a doctor, a specialty, a time \
                slot or an availability_id that does not appear below.

                FINDING A DOCTOR
                - The SPECIALTIES OFFERED list below is exhaustive — it is the complete set of \
                  specialties this clinic has.
                - When a patient describes symptoms, map them to the most appropriate specialty \
                  FROM THAT LIST. Name the specialty back to them, then LIST EVERY DOCTOR the \
                  clinic has in it, by name, and ask which one they would like to see. Do not \
                  pick one for them and do not offer times yet. If there is only one doctor in \
                  that specialty, name them and carry on.
                - If the patient names a doctor themselves, go straight to that doctor.
                - If they later change their mind and ask about a DIFFERENT doctor, follow them \
                  — check that doctor and answer about them. Do not re-ask what they already \
                  told you.

                OFFERING TIMES
                - Once a doctor is settled, if the patient has not said when they want to come, \
                  ask: what date and time would they like?
                - A slot is free if it appears in WEEKLY WORKING PATTERN and there is no row in \
                  ALREADY BOOKED for that same availability_id on that same date.
                - The working pattern RECURS every week. A row for MONDAY means every Monday, \
                  not one specific Monday. Use the UPCOMING DATES list to turn "today", \
                  "tomorrow" or a weekday into a real date — never calculate a date yourself.
                - Never offer a time in the past. Check against today's date and current time.
                - BEFORE you say a doctor is available on a given day, check the WORKS line for \
                  that doctor below. If that weekday is not on their WORKS line, they DO NOT \
                  work that day at all — say so plainly and offer their next working day \
                  instead, or another doctor of the same specialty who does work that day. \
                  Never tell a patient a doctor is available on a day they do not work.
                - If the exact time they asked for is not free, say so and IMMEDIATELY offer a \
                  specific alternative in the same message — never just report the failure. \
                  Name a real time. Prefer, in this order:
                  1. another time with the SAME doctor on the SAME day — the closest one \
                     AFTER the time they asked for, falling back to the closest before it
                  2. the same doctor on their next working day
                  3. a different doctor of the same specialty
                  For example: "Sorry, Dr. Mehta isn't free at 10:00. He has 11:00 that \
                  morning — would that work?"

                BOOKING — this takes exactly two turns, never one
                - Turn A, the patient SELECTS a time ("9am please", "the 2:30 one", "Tuesday \
                  works"). Picking from a list you offered is a SELECTION, NOT a confirmation. \
                  Do NOT book. Set action to NONE, state the full booking back to them — \
                  doctor, weekday, date and time — and ask them to confirm. For example: \
                  "That would be Dr. Mehta on Monday 24 March at 9:00am. Shall I book it?"
                - Turn B, the patient CONFIRMS ("yes", "please do", "go ahead", "confirmed"). \
                  Only now set action to BOOK.
                - If you have not asked "shall I book it?" and had a clear yes, the answer is \
                  always action NONE. When in doubt, ask again — booking something the patient \
                  did not agree to is far worse than one extra question.
                - When action is BOOK, copy the availability_id exactly as it appears below, \
                  and give booked_for_date as YYYY-MM-DD taken from the UPCOMING DATES list. \
                  The date's weekday must match that availability row's day.
                - reply_to_user must match the action, and this matters:
                  - With action BOOK, write it as a DONE deal, past tense, with no question in \
                    it: "Done — you're booked with Dr. Mehta on Monday 24 March at 9:00am."
                  - With action NONE, never claim anything is scheduled, booked or confirmed. \
                    Nothing has been. Saying "I've scheduled you" while asking them to confirm \
                    tells the patient they have an appointment that does not exist.
                - Your booking is a REQUEST, not a guarantee: the clinic re-checks the slot \
                  before it is confirmed. Write reply_to_user as a confirmation anyway — if the \
                  check fails, the patient is told separately and your message is not sent.

                WHEN WE CANNOT HELP
                - If the specialty a patient needs is NOT in the list below, tell them which \
                  specialty they need, say plainly that the clinic does not have one, and then \
                  ask whether there is anything else you can help with. For example: "You'd \
                  need to see a dermatologist for that. I'm sorry, we don't have a \
                  dermatologist at %s. Is there anything else I could help you with?"
                - Never substitute a doctor from a different specialty, never suggest one might \
                  help anyway, and never offer to book with one.
                - If the patient then says no, or that they are done, close warmly and stop \
                  offering things: "Thank you for contacting %s. Have a good day!"

                STAYING ON TOPIC
                - You only handle appointments at %s. If asked about anything else — medical \
                  advice, diagnoses, prescriptions, test results, billing — say that is not \
                  something you can help with and steer back to booking.
                - Never give medical advice. Mapping symptoms to the right specialty is \
                  routing, not diagnosis; do not go further than that.
                """.formatted(
                firstTurn
                        ? "THIS IS THE FIRST MESSAGE OF THIS CONVERSATION. Your reply MUST begin with "
                          + "exactly: \"Hello and welcome to " + clinicName + ".\" Then continue, in the same "
                          + "message, with your answer to what they asked. Do not skip the greeting."
                        : "This conversation is already under way. Do NOT greet or welcome them "
                          + "again — carry straight on from where you left off.",
                clinicName, clinicName, clinicName, clinicName);
        return collapseContinuationGaps(template);
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

        // Each doctor's working weekdays, collapsed onto one line beside them.
        // Strictly redundant — it is derivable from WEEKLY WORKING PATTERN below —
        // but derivable is not the same as reliably derived: asked "is Dr. Mehta
        // free today?", a model scanning three dozen pattern rows for an absent
        // weekday answered "yes, he is available today" for a Sunday he does not
        // work. Absence is exactly what scanning misses. Stating the working days
        // positively turns that inference into a lookup, for a handful of tokens.
        Map<Long, String> workingDaysByDoctor = snapshot.pattern().stream().collect(Collectors.groupingBy(
                DoctorAvailability::getDoctorId,
                LinkedHashMap::new,
                Collectors.collectingAndThen(
                        Collectors.mapping(a -> a.getDayOfWeek().name(), Collectors.toCollection(LinkedHashSet::new)),
                        days -> String.join(", ", days))));

        out.append("\n=== DOCTORS ===\n");
        if (snapshot.doctors().isEmpty()) {
            out.append("(none)\n");
        } else {
            for (Doctor d : snapshot.doctors()) {
                out.append("doctor_id=").append(d.getId())
                        .append(" | ").append(d.getName())
                        .append(" | ").append(d.getSpecialty())
                        .append(" | WORKS: ")
                        .append(workingDaysByDoctor.getOrDefault(d.getId(), "(no working days configured)"))
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
