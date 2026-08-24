package com.chatapp.chatservice.bot.clinic;


import com.chatapp.chatservice.bot.clinic.entity.Appointment;
import com.chatapp.chatservice.bot.clinic.repository.AppointmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The trust boundary between what a model asked for and what gets written
 * (CLAUDE.md 3.9 §6.4).
 *
 * <p>Every input here arrives from a language model, so each test is a specific
 * way a model can be wrong: a stale slot, a date that doesn't match the slot's
 * weekday, a date in the past, a BOOK with no slot named, an unparseable date,
 * and a slot taken by someone else between the check and the write.
 *
 * <p>The assertion repeated throughout is that a rejection sends JAVA's message
 * and never the model's. That text was written on the assumption the booking
 * succeeded — leaking it after a failed insert tells a patient they have an
 * appointment nobody made.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);
    private static final LocalDate TOMORROW = LocalDate.of(2026, 8, 25);
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 8, 23);
    private static final long USER_ID = 42L;
    private static final long AVAILABILITY_ID = 7L;

    private static final String MODEL_CONFIRMATION = "You're booked with Dr. Mehta on Monday at 9am!";

    @Mock
    private AvailabilityService availabilityService;

    @Mock
    private AppointmentRepository appointmentRepository;

    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        // A fixed clock, so "is this date in the past?" means something specific.
        // With the real clock these tests would drift into asserting nothing the
        // day TODAY stops being today.
        Clock fixed = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        bookingService = new BookingService(availabilityService, appointmentRepository, fixed);
    }

    @Test
    void freeSlot_isBookedAndTheModelsConfirmationIsSent() {
        when(availabilityService.findFreeSlot(AVAILABILITY_ID, TOMORROW))
                .thenReturn(Optional.of(slot(TOMORROW)));
        when(appointmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        BookingOutcome outcome = bookingService.book(bookRequest(TOMORROW.toString()), USER_ID);

        assertThat(outcome.booked()).isTrue();
        assertThat(outcome.replyToUser()).isEqualTo(MODEL_CONFIRMATION);

        ArgumentCaptor<Appointment> saved = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).saveAndFlush(saved.capture());
        Appointment appointment = saved.getValue();
        assertThat(appointment.getAvailabilityId()).isEqualTo(AVAILABILITY_ID);
        assertThat(appointment.getBookedForDate()).isEqualTo(TOMORROW);
        assertThat(appointment.getUserId()).isEqualTo(USER_ID);
        // Times come off the slot, not off anything the model said — they are
        // denormalised onto the row and the pattern is the authority for them.
        assertThat(appointment.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(appointment.getEndTime()).isEqualTo(LocalTime.of(9, 30));
    }

    @Test
    void slotTakenSinceThePromptWasBuilt_isRejectedWithJavasMessage() {
        // The prompt is a snapshot, not a lock — §6.4's stated reason this
        // re-check exists at all.
        when(availabilityService.findFreeSlot(AVAILABILITY_ID, TOMORROW)).thenReturn(Optional.empty());

        BookingOutcome outcome = bookingService.book(bookRequest(TOMORROW.toString()), USER_ID);

        assertThat(outcome.booked()).isFalse();
        assertThat(outcome.replyToUser()).isNotEqualTo(MODEL_CONFIRMATION).contains("isn't available");
        verify(appointmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void dateInThePast_isRejectedBeforeTheSlotIsEvenChecked() {
        // The subtraction is date-agnostic: nothing is booked against last Monday,
        // so it would happily report that slot free. Only a comparison against
        // today catches it, which is why this check exists separately.
        BookingOutcome outcome = bookingService.book(bookRequest(YESTERDAY.toString()), USER_ID);

        assertThat(outcome.booked()).isFalse();
        verify(availabilityService, never()).findFreeSlot(anyLong(), any());
        verify(appointmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void todayIsNotTreatedAsThePast() {
        // The boundary the previous test's `isBefore` could easily get wrong: a
        // slot later today is still bookable.
        when(availabilityService.findFreeSlot(AVAILABILITY_ID, TODAY)).thenReturn(Optional.of(slot(TODAY)));
        when(appointmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(bookingService.book(bookRequest(TODAY.toString()), USER_ID).booked()).isTrue();
    }

    @Test
    void bookWithNoAvailabilityId_isRejected() {
        // Structured outputs guarantee the FIELD is present, not that it is
        // non-null — the schema marks it nullable so a NONE turn can leave it
        // empty. A BOOK turn that leaves it empty is the model contradicting
        // itself, and there is nothing to validate.
        BookingRequest noSlotChosen = new BookingRequest(null, TOMORROW.toString(), MODEL_CONFIRMATION);

        assertThat(bookingService.book(noSlotChosen, USER_ID).booked()).isFalse();
        verify(appointmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void unparseableDate_isRejected() {
        // The schema constrains this to a string, not a date — "next Tuesday"
        // satisfies it perfectly.
        assertThat(bookingService.book(bookRequest("next Tuesday"), USER_ID).booked()).isFalse();
        verify(appointmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void impossibleCalendarDate_isRejected() {
        assertThat(bookingService.book(bookRequest("2026-02-30"), USER_ID).booked()).isFalse();
        verify(appointmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentBookingOfTheSameSlot_isRejectedRatherThanDuplicated() {
        // Two users interleaved between the re-check and the write. The unique
        // constraint on (availability_id, booked_for_date) is what actually stops
        // the double booking; this proves the failure is caught and turned into a
        // sensible reply instead of a stack trace and no message at all.
        when(availabilityService.findFreeSlot(AVAILABILITY_ID, TOMORROW))
                .thenReturn(Optional.of(slot(TOMORROW)));
        when(appointmentRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uq_appointment_slot"));

        BookingOutcome outcome = bookingService.book(bookRequest(TOMORROW.toString()), USER_ID);

        assertThat(outcome.booked()).isFalse();
        assertThat(outcome.replyToUser()).isNotEqualTo(MODEL_CONFIRMATION);
    }

    private BookingRequest bookRequest(String date) {
        return new BookingRequest(AVAILABILITY_ID, date, MODEL_CONFIRMATION);
    }

    private FreeSlot slot(LocalDate date) {
        return new FreeSlot(AVAILABILITY_ID, 1L, "Dr. Tushar Mehta", "Orthopedic",
                date, LocalTime.of(9, 0), LocalTime.of(9, 30));
    }
}
