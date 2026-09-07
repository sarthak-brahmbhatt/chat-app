package com.chatapp.chatservice.bot.clinic;

import com.chatapp.chatservice.bot.clinic.entity.Appointment;
import com.chatapp.chatservice.bot.clinic.entity.Doctor;
import com.chatapp.chatservice.bot.clinic.entity.DoctorAvailability;
import com.chatapp.chatservice.bot.clinic.repository.AppointmentRepository;
import com.chatapp.chatservice.bot.clinic.repository.DoctorAvailabilityRepository;
import com.chatapp.chatservice.bot.clinic.repository.DoctorRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The availability subtraction (CLAUDE.md 3.9 §4), against a real database.
 *
 * <p>Runs on H2 rather than mocks because the whole thing under test IS a query:
 * a three-table join with a NOT EXISTS and enum parameters, projected into a
 * record. A mocked repository would only prove Mockito returns what it was told.
 *
 * <p>Each filter gets its own test because §4 names the specific way this goes
 * wrong — a second version of the query that subtracts booked appointments but
 * forgets {@code doctors.active}. That bug passes any test that only checks
 * booked slots disappear, so "an inactive doctor's slots disappear" has to be its
 * own assertion.
 */
@DataJpaTest
@Import(AvailabilityService.class)
class AvailabilityServiceTest {

    // A fixed Monday. Every date here is a real, known weekday — the pattern is
    // per-weekday, so a test built on "today" would exercise a different branch
    // depending on which day it ran.
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 8, 25);
    private static final LocalDate NEXT_MONDAY = LocalDate.of(2026, 8, 31);

    @Autowired
    private AvailabilityService availabilityService;

    @Autowired
    private DoctorRepository doctorRepository;

    @Autowired
    private DoctorAvailabilityRepository availabilityRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private EntityManager entityManager;

    private Doctor mehta;
    private DoctorAvailability mondayNine;

    @BeforeEach
    void setUp() {
        mehta = doctorRepository.save(new Doctor("Dr. Tushar Mehta", "Orthopedic"));
        mondayNine = availabilityRepository.save(new DoctorAvailability(
                mehta.getId(), DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(9, 30)));
    }

    @Test
    void patternSlotWithNoBooking_isFree() {
        List<FreeSlot> free = availabilityService.freeSlotsOn(MONDAY);

        assertThat(free).singleElement().satisfies(slot -> {
            assertThat(slot.availabilityId()).isEqualTo(mondayNine.getId());
            assertThat(slot.doctorName()).isEqualTo("Dr. Tushar Mehta");
            assertThat(slot.specialty()).isEqualTo("Orthopedic");
            // The date is not stored on the pattern row — it is the parameter the
            // query was asked about, projected back out so a FreeSlot is
            // self-describing.
            assertThat(slot.date()).isEqualTo(MONDAY);
            assertThat(slot.startTime()).isEqualTo(LocalTime.of(9, 0));
        });
    }

    @Test
    void patternSlotOnADifferentWeekday_isNotOffered() {
        assertThat(availabilityService.freeSlotsOn(TUESDAY)).isEmpty();
    }

    @Test
    void bookedSlot_isSubtractedOnThatDateOnly() {
        book(mondayNine, MONDAY);

        assertThat(availabilityService.freeSlotsOn(MONDAY)).isEmpty();

        // The pattern RECURS: booking one Monday must not consume every Monday.
        // This is the assertion that would fail if the NOT EXISTS ever lost its
        // bookedForDate predicate, which is an easy thing to drop and produces a
        // clinic that fills up permanently after one booking.
        assertThat(availabilityService.freeSlotsOn(NEXT_MONDAY))
                .singleElement()
                .satisfies(slot -> assertThat(slot.date()).isEqualTo(NEXT_MONDAY));
    }

    @Test
    void inactiveDoctorsSlots_areNotOffered() {
        // §4's named failure mode: a query that subtracts bookings correctly but
        // forgets doctors.active looks right and silently offers appointments
        // with a doctor who is on leave.
        deactivate(mehta);

        assertThat(availabilityService.freeSlotsOn(MONDAY)).isEmpty();
    }

    @Test
    void blockedSlot_isNotOffered() {
        block(mondayNine);

        assertThat(availabilityService.freeSlotsOn(MONDAY)).isEmpty();
    }

    @Test
    void bookingAgainstAnInactiveDoctor_isStillHonoured() {
        // The forward/backward asymmetry §4 insists on: a soft delete stops NEW
        // bookings and never cancels existing ones. Reading what is booked must
        // therefore ignore `active` entirely — if this ever started filtering,
        // a patient with a real appointment would be told they have none.
        book(mondayNine, MONDAY);
        deactivate(mehta);

        assertThat(availabilityService.freeSlotsOn(MONDAY)).isEmpty();
        assertThat(appointmentRepository.findInWindow(
                com.chatapp.chatservice.bot.clinic.entity.AppointmentStatus.BOOKED, MONDAY, MONDAY))
                .hasSize(1);
    }

    @Test
    void findFreeSlot_returnsEmptyForEveryUnbookableReason() {
        // One assertion per reason, all reaching the same answer: don't book.
        // findFreeSlot deliberately cannot say WHICH of these it was, because no
        // caller branches on it.
        assertThat(availabilityService.findFreeSlot(mondayNine.getId(), MONDAY)).isPresent();

        // Wrong weekday for this pattern row.
        assertThat(availabilityService.findFreeSlot(mondayNine.getId(), TUESDAY)).isEmpty();

        // No such availability row at all.
        assertThat(availabilityService.findFreeSlot(999_999L, MONDAY)).isEmpty();

        // Already taken.
        book(mondayNine, MONDAY);
        assertThat(availabilityService.findFreeSlot(mondayNine.getId(), MONDAY)).isEmpty();
    }

    @Test
    void onlyBookedAppointmentsSubtract_soACancelledOneFreesTheSlot() {
        // Cancellation is out of scope as a FEATURE, but the query is already
        // written to scope its subtraction to BOOKED rather than "any row". This
        // pins that down so a later cancellation flow only has to change a status
        // — and so nobody simplifies the predicate away in the meantime.
        Appointment cancelled = book(mondayNine, MONDAY);
        entityManager.createQuery("UPDATE Appointment a SET a.status = :s WHERE a.id = :id")
                .setParameter("s", com.chatapp.chatservice.bot.clinic.entity.AppointmentStatus.CANCELLED_BY_USER)
                .setParameter("id", cancelled.getId())
                .executeUpdate();
        entityManager.clear();

        assertThat(availabilityService.freeSlotsOn(MONDAY)).hasSize(1);
    }

    @Test
    void multipleDoctorsOnOneDay_areAllReturnedOrderedByName() {
        Doctor nair = doctorRepository.save(new Doctor("Dr. Priya Nair", "Cardiology"));
        availabilityRepository.save(new DoctorAvailability(
                nair.getId(), DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(8, 30)));

        List<FreeSlot> free = availabilityService.freeSlotsOn(MONDAY);

        // Ordered by doctor name, then start time — deliberately not by time
        // across doctors. The prompt groups a doctor's slots together, which is
        // what makes "another time with the same doctor" easy for the model to
        // read off.
        assertThat(free).extracting(FreeSlot::doctorName)
                .containsExactly("Dr. Priya Nair", "Dr. Tushar Mehta");
    }

    private Appointment book(DoctorAvailability slot, LocalDate date) {
        return appointmentRepository.saveAndFlush(new Appointment(
                slot.getDoctorId(), slot.getId(), date,
                slot.getStartTime(), slot.getEndTime(), 42L, Instant.now()));
    }

    // Both soft deletes are applied by JPQL rather than through a setter, because
    // neither entity has one: the production code has no path that deactivates a
    // doctor or blocks a slot (it would be an admin feature, which does not
    // exist), and adding mutators only tests could reach would misrepresent that.
    private void deactivate(Doctor doctor) {
        entityManager.createQuery("UPDATE Doctor d SET d.active = false WHERE d.id = :id")
                .setParameter("id", doctor.getId())
                .executeUpdate();
        entityManager.clear();
    }

    private void block(DoctorAvailability slot) {
        entityManager.createQuery("UPDATE DoctorAvailability a SET a.status = :s WHERE a.id = :id")
                .setParameter("s", com.chatapp.chatservice.bot.clinic.entity.AvailabilityStatus.BLOCKED)
                .setParameter("id", slot.getId())
                .executeUpdate();
        entityManager.clear();
    }
}
