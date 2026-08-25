package com.chatapp.chatservice.bot.toolcalling;

import com.chatapp.chatservice.bot.clinic.AvailabilityService;
import com.chatapp.chatservice.bot.clinic.BookingService;
import com.chatapp.chatservice.bot.clinic.entity.Appointment;
import com.chatapp.chatservice.bot.clinic.entity.Doctor;
import com.chatapp.chatservice.bot.clinic.entity.DoctorAvailability;
import com.chatapp.chatservice.bot.clinic.repository.AppointmentRepository;
import com.chatapp.chatservice.bot.clinic.repository.DoctorAvailabilityRepository;
import com.chatapp.chatservice.bot.clinic.repository.DoctorRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tool executor, against a real database (CLAUDE.md 3.10).
 *
 * <p>Runs on H2 for the same reason {@code AvailabilityServiceTest} does: what
 * these tools do IS database work, and mocking the repositories would assert
 * only that Mockito returns what it was told.
 *
 * <p>The two tests that matter most are the ones covering Version 1's failures.
 * Version 2 is supposed to make both unreachable rather than merely less likely,
 * so they are asserted directly rather than trusted to the model.
 */
@DataJpaTest
@Import({AvailabilityService.class})
class ClinicToolExecutorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);   // a Monday
    private static final long ME = 42L;
    private static final long SOMEONE_ELSE = 99L;

    @Autowired private DoctorRepository doctorRepository;
    @Autowired private DoctorAvailabilityRepository availabilityRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private AvailabilityService availabilityService;

    private final ObjectMapper mapper = new ObjectMapper();
    private ClinicToolExecutor executor;
    private Doctor mehta;
    private DoctorAvailability nine;
    private DoctorAvailability nineThirty;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

        mehta = doctorRepository.save(new Doctor("Dr. Tushar Mehta", "Orthopedic"));
        nine = availabilityRepository.save(new DoctorAvailability(
                mehta.getId(), DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(9, 30)));
        nineThirty = availabilityRepository.save(new DoctorAvailability(
                mehta.getId(), DayOfWeek.MONDAY, LocalTime.of(9, 30), LocalTime.of(10, 0)));

        executor = new ClinicToolExecutor(
                doctorRepository, availabilityRepository, appointmentRepository,
                availabilityService, new BookingService(availabilityService, appointmentRepository, fixed),
                mapper, fixed, 7, ME);
    }

    @Test
    void getMyAppointments_cannotSeeAnotherPatientsBooking() throws Exception {
        // Version 1's cross-patient leak, made structurally unreachable. There
        // is no argument that could widen this call — the patient id is fixed
        // when the executor is built, from the authenticated session.
        book(nine, SOMEONE_ELSE);

        JsonNode result = mapper.readTree(executor.execute("get_my_appointments", "{}"));

        assertThat(result.get("appointments")).isEmpty();
        assertThat(result.get("note").asText()).contains("no appointments booked");
    }

    @Test
    void getMyAppointments_returnsTheCallersOwn() throws Exception {
        book(nine, ME);
        book(nineThirty, SOMEONE_ELSE);

        JsonNode result = mapper.readTree(executor.execute("get_my_appointments", "{}"));

        assertThat(result.get("appointments")).hasSize(1);
        assertThat(result.get("appointments").get(0).get("start").asText()).isEqualTo("09:00");
    }

    @Test
    void getAvailableSlots_excludesWhatIsAlreadyTaken() throws Exception {
        // Version 1's other failure: it offered a slot that was in its own
        // booked list, having done the subtraction itself and got it wrong.
        // Here the subtraction is SQL and the model only sees the result.
        book(nine, SOMEONE_ELSE);

        JsonNode result = mapper.readTree(
                executor.execute("get_available_slots", "{\"date\":\"2026-08-24\"}"));

        assertThat(result.get("available_slots")).hasSize(1);
        assertThat(result.get("available_slots").get(0).get("start").asText()).isEqualTo("09:30");
        assertThat(result.get("weekday").asText()).isEqualTo("MONDAY");
    }

    @Test
    void getAvailableSlots_refusesAPastDateWithAnExplanationTheModelCanActOn() throws Exception {
        JsonNode result = mapper.readTree(
                executor.execute("get_available_slots", "{\"date\":\"2026-08-01\"}"));

        // An error the model can read and recover from, not an exception that
        // kills the turn.
        assertThat(result.hasNonNull("error")).isTrue();
        assertThat(result.get("error").asText()).contains("in the past");
    }

    @Test
    void getAvailableSlots_refusesBeyondTheBookingHorizon() throws Exception {
        JsonNode result = mapper.readTree(
                executor.execute("get_available_slots", "{\"date\":\"2027-01-01\"}"));

        assertThat(result.get("error").asText()).contains("only open up to");
    }

    @Test
    void getAvailableSlots_refusesGarbageRatherThanThrowing() throws Exception {
        JsonNode result = mapper.readTree(
                executor.execute("get_available_slots", "{\"date\":\"next Tuesday\"}"));

        assertThat(result.get("error").asText()).contains("YYYY-MM-DD");
    }

    @Test
    void bookAppointment_writesTheRowAndIsScopedToTheCaller() throws Exception {
        JsonNode result = mapper.readTree(executor.execute("book_appointment",
                "{\"availability_id\":" + nine.getId() + ",\"booked_for_date\":\"2026-08-24\"}"));

        assertThat(result.get("booked").asBoolean()).isTrue();
        assertThat(appointmentRepository.findAll()).singleElement().satisfies(a -> {
            assertThat(a.getUserId()).isEqualTo(ME);      // never from the model
            assertThat(a.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        });
    }

    @Test
    void bookAppointment_stillGoesThroughEveryValidation() throws Exception {
        // The model can request a booking; it cannot make one. BookingService
        // refuses exactly as it does for Version 1 — same code, reached a
        // different way.
        book(nine, SOMEONE_ELSE);

        JsonNode result = mapper.readTree(executor.execute("book_appointment",
                "{\"availability_id\":" + nine.getId() + ",\"booked_for_date\":\"2026-08-24\"}"));

        assertThat(result.get("booked").asBoolean()).isFalse();
        assertThat(appointmentRepository.findAll()).hasSize(1);   // no duplicate
    }

    @Test
    void findDoctors_saysSoWhenTheSpecialtyIsNotOffered() throws Exception {
        JsonNode result = mapper.readTree(
                executor.execute("find_doctors", "{\"specialty\":\"Dermatology\"}"));

        // An empty array alone would not distinguish "no doctors" from "no such
        // specialty", and those lead to different replies.
        assertThat(result.get("doctors")).isEmpty();
        assertThat(result.get("note").asText()).contains("no doctors in 'Dermatology'");
    }

    @Test
    void findDoctors_reportsWorkingDaysAlongsideEachDoctor() throws Exception {
        JsonNode result = mapper.readTree(executor.execute("find_doctors", "{\"specialty\":null}"));

        assertThat(result.get("doctors")).hasSize(1);
        assertThat(result.get("doctors").get(0).get("works_on").asText()).contains("MONDAY");
    }

    @Test
    void listSpecialties_returnsWhatTheClinicActuallyHas() throws Exception {
        JsonNode result = mapper.readTree(executor.execute("list_specialties", "{}"));

        assertThat(result.get("specialties")).hasSize(1);
        assertThat(result.get("specialties").get(0).asText()).isEqualTo("Orthopedic");
    }

    @Test
    void anInventedToolNameIsAnAnswer_notACrash() throws Exception {
        JsonNode result = mapper.readTree(executor.execute("cancel_everything", "{}"));

        assertThat(result.get("error").asText()).contains("No such tool");
    }

    private void book(DoctorAvailability slot, long userId) {
        appointmentRepository.saveAndFlush(new Appointment(
                slot.getDoctorId(), slot.getId(), TODAY,
                slot.getStartTime(), slot.getEndTime(), userId, Instant.now()));
    }
}
