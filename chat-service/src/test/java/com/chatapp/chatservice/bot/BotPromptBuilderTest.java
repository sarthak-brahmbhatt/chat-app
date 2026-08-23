package com.chatapp.chatservice.bot;

import com.chatapp.chatservice.bot.entity.Appointment;
import com.chatapp.chatservice.bot.entity.Doctor;
import com.chatapp.chatservice.bot.entity.DoctorAvailability;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stuffed prompt (CLAUDE.md 3.9 §6.1, §6.2).
 *
 * <p>Asserts the facts the model cannot function without, not the prose around
 * them: the ids it has to echo back, the closed specialty list, the date→weekday
 * mapping, and the horizon. Pinning the wording would make every prompt tweak a
 * test failure, which is the fastest way to a test nobody trusts.
 */
class BotPromptBuilderTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);

    private final BotPromptBuilder builder = new BotPromptBuilder("Super Clinic");

    @Test
    void specialtiesAreListedAsAClosedSet() {
        String prompt = builder.systemPrompt(snapshot(List.of("Cardiology", "Orthopedic")), true);

        // The closed list plus the instruction never to leave it is the entire
        // mechanism behind "sorry, we have no dermatologist" — without it a model
        // asked for one will invent it, because inventing is a far more fluent
        // continuation than refusing.
        assertThat(prompt).contains("Cardiology, Orthopedic");
        assertThat(prompt).contains("the clinic has no others");
        assertThat(prompt).contains("Never invent a doctor, a specialty, a time slot or an availability_id");
    }

    @Test
    void everyBookableSlotCarriesTheIdTheModelMustEchoBack() {
        String prompt = builder.systemPrompt(snapshot(List.of("Orthopedic")), true);

        // §6.3 has the model return an availability_id verbatim. If the id is not
        // in the prompt beside the slot, there is nothing correct for it to
        // return and it will produce a plausible number instead.
        assertThat(prompt).contains("availability_id=11");
        assertThat(prompt).contains("MONDAY | 09:00-09:30");
    }

    @Test
    void todayAndTheUpcomingDatesAreSpelledOut() {
        String prompt = builder.systemPrompt(snapshot(List.of("Orthopedic")), true);

        // The model has neither a clock nor a calendar. Both have to be given, or
        // "next Tuesday" resolves to a confident and frequently wrong date.
        assertThat(prompt).contains("Date: 2026-08-24 (MONDAY)");
        assertThat(prompt).contains("2026-08-24 MONDAY (today)");
        assertThat(prompt).contains("2026-08-26 WEDNESDAY");
        assertThat(prompt).contains("do not calculate dates yourself");
    }

    @Test
    void existingBookingsAreListedForSubtraction() {
        Appointment booked = new Appointment(
                1L, 11L, MONDAY, LocalTime.of(9, 0), LocalTime.of(9, 30), 42L, Instant.now());

        String prompt = builder.systemPrompt(snapshotWith(List.of(booked)), true);

        assertThat(prompt).contains("ALREADY BOOKED");
        assertThat(prompt).contains("2026-08-24 | availability_id=11");
    }

    @Test
    void anEmptyBookingListSaysSoRatherThanShowingNothing() {
        String prompt = builder.systemPrompt(snapshot(List.of("Orthopedic")), true);

        // An empty heading reads as missing data, and a model that thinks the
        // booking list is missing hedges instead of offering the slot.
        assertThat(prompt).contains("Nothing is booked in this window");
    }

    @Test
    void theHorizonIsStatedSoFarFutureSlotsAreNotOfferedBlindly() {
        String prompt = builder.systemPrompt(snapshot(List.of("Orthopedic")), true);

        // Bookings are only loaded up to the horizon, so beyond it the absence of
        // a booking proves nothing.
        assertThat(prompt).contains("Do not offer appointments beyond 2026-08-30");
    }

    @Test
    void bookingIsSpelledOutAsTwoTurns() {
        String prompt = builder.systemPrompt(snapshot(List.of("Orthopedic")), true);

        // Found in live testing: told only "BOOK once the patient has agreed to a
        // slot you offered", the model books on "9am please" — picking from a
        // list reads as agreement. §6.2 wants an explicit confirmation, so the
        // prompt has to name selection and confirmation as separate turns rather
        // than leaving the model to draw the line.
        assertThat(prompt).contains("SELECTION, NOT a confirmation");
        assertThat(prompt).contains("Shall I book it?");
        assertThat(prompt).contains("Only now set action to BOOK");
    }

    @Test
    void theReplyTextIsRequiredToMatchTheAction() {
        String prompt = builder.systemPrompt(snapshot(List.of("Orthopedic")), true);

        // The same live failure produced "I've scheduled your appointment...
        // Please confirm if this works for you!" on a NONE turn — a booking
        // claimed and permission requested in one breath, describing an
        // appointment that did not exist.
        assertThat(prompt).contains("never claim anything is scheduled, booked or confirmed");
    }

    @Test
    void theThreeSampleConversationsAreEachAddressed() {
        // docs/bot-requirements.md lists three conversations as the target
        // behaviour. Each needs a rule the model can actually follow, and each of
        // these was a real gap found by replaying the samples against the bot.
        String prompt = builder.systemPrompt(snapshot(List.of("Orthopedic")), true);

        // (i) named doctor, no time given -> ask for one; requested time taken ->
        // name a concrete alternative rather than just reporting the failure.
        assertThat(prompt).contains("what date and time would they like");
        assertThat(prompt).contains("IMMEDIATELY offer a specific alternative");

        // (ii) specialty not offered -> say so, offer nothing else, then ask if
        // there is anything else, then sign off.
        assertThat(prompt).contains("Is there anything else I could help you with?");
        assertThat(prompt).contains("Have a good day!");

        // (iii) symptoms -> specialty -> list EVERY doctor in it and let the
        // patient choose, rather than picking one for them.
        assertThat(prompt).contains("LIST EVERY DOCTOR");
        assertThat(prompt).contains("Do not pick one for them");
    }

    @Test
    void theFirstTurnIsToldToWelcome_andLaterTurnsAreToldNotTo() {
        // Every sample conversation starts "Hello and welcome to the Super
        // Clinic". The bot cannot speak first in a chat window, so the welcome
        // rides on its first reply instead.
        //
        // Told as a fact rather than left to the model: with no
        // previous_response_id it merely sees no history, which is not the same
        // as knowing it is turn one. In live testing it welcomed on some first
        // turns and skipped it on others.
        String first = builder.systemPrompt(snapshot(List.of("Orthopedic")), true);
        // Asserted as the very FIRST line, not merely present: the same
        // instruction sat in a mid-prompt "OPENING" section and was followed on
        // some first turns and ignored on others, losing to the dozen rules
        // after it. Position is the fix, so position is what the test pins.
        assertThat(first).startsWith("THIS IS THE FIRST MESSAGE OF THIS CONVERSATION.");
        assertThat(first).contains("Hello and welcome to Super Clinic.");

        String later = builder.systemPrompt(snapshot(List.of("Orthopedic")), false);
        assertThat(later).contains("already under way");
        assertThat(later).contains("Do NOT greet or welcome them again");
    }

    @Test
    void eachDoctorsWorkingDaysAreStatedPositively() {
        String prompt = builder.systemPrompt(snapshot(List.of("Orthopedic")), true);

        // Derivable from the pattern rows, but derivable is not reliably derived:
        // asked "is Dr. Mehta free today?" on a Sunday he does not work, the model
        // scanned the pattern, failed to notice the ABSENCE of Sunday rows, and
        // answered "yes, he is available today". Stating the working days turns
        // that inference into a lookup.
        assertThat(prompt).contains("WORKS: MONDAY");
        assertThat(prompt).contains("they DO NOT work that day at all");
    }

    @Test
    void theClinicNameIsConfiguredNotHardcoded() {
        String prompt = new BotPromptBuilder("Riverside Health").systemPrompt(snapshot(List.of("Orthopedic")), true);

        assertThat(prompt).contains("Riverside Health").doesNotContain("Super Clinic");
    }

    private ClinicSnapshot snapshot(List<String> specialties) {
        return build(specialties, List.of());
    }

    private ClinicSnapshot snapshotWith(List<Appointment> bookings) {
        return build(List.of("Orthopedic"), bookings);
    }

    private ClinicSnapshot build(List<String> specialties, List<Appointment> bookings) {
        Doctor mehta = doctorWithId(1L, "Dr. Tushar Mehta", "Orthopedic");
        DoctorAvailability slot = availabilityWithId(11L, 1L);

        return new ClinicSnapshot(
                MONDAY, LocalTime.of(9, 0), specialties, List.of(mehta), List.of(slot), bookings,
                MONDAY.plusDays(6));
    }

    // Ids are normally assigned by the database, and these entities have no
    // setter for them — correctly, since nothing in production should reassign a
    // primary key. Reflection is the honest way to build a fixture that looks
    // like a persisted row without weakening the entity to suit a test.
    private Doctor doctorWithId(long id, String name, String specialty) {
        Doctor doctor = new Doctor(name, specialty);
        setId(doctor, id);
        return doctor;
    }

    private DoctorAvailability availabilityWithId(long id, long doctorId) {
        DoctorAvailability availability = new DoctorAvailability(
                doctorId, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(9, 30));
        setId(availability, id);
        return availability;
    }

    private void setId(Object entity, long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not set id on " + entity.getClass(), e);
        }
    }
}
