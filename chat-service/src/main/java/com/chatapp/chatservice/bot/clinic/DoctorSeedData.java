package com.chatapp.chatservice.bot.clinic;

import com.chatapp.chatservice.bot.clinic.entity.Doctor;
import com.chatapp.chatservice.bot.clinic.entity.DoctorAvailability;
import com.chatapp.chatservice.bot.clinic.repository.DoctorAvailabilityRepository;
import com.chatapp.chatservice.bot.clinic.repository.DoctorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/**
 * Seeds the clinic's doctors and their weekly working hours (CLAUDE.md 3.9 §6.1).
 *
 * <p>Java rather than SQL in {@code mysql-init/}, for a specific reason: those
 * scripts run against an EMPTY data volume, before any service has started, and
 * Hibernate's {@code ddl-auto:update} does not create these tables until
 * chat-service boots. A seed script there would be inserting into tables that do
 * not exist yet. Running after the schema is up is the only place this can work
 * without also taking schema ownership away from Hibernate.
 *
 * <p>Idempotent, and re-checked on every boot rather than gated on a one-shot
 * flag: the existence check makes a repeat run one cheap query, and it means
 * deleting a doctor by hand while poking at the bot self-heals on restart
 * instead of leaving a permanently half-seeded clinic.
 *
 * <p>This is demo data. Deliberately five doctors across four specialties — few
 * enough that stuffing all of it into every prompt is genuinely fine at this
 * size, which is Version 1's whole premise, and varied enough to exercise the
 * cases that matter: two doctors sharing a specialty (so "try another doctor of
 * the same specialty" has somewhere to go), and specialties a patient will
 * plausibly ask for that are NOT here (dermatology, most obviously) so the
 * refusal path gets hit.
 */
@Component
// On by default — a fresh `docker compose up` should land with a usable clinic,
// not an empty one somebody has to populate by hand before the bot does anything.
// The switch exists for the full-context tests that mock these repositories away:
// with a mock, existsByNameAndSpecialty says "no" and save() returns null, so an
// unguarded seeder NPEs during context startup and takes the whole test with it.
@ConditionalOnProperty(name = "bot.seed-demo-data", havingValue = "true", matchIfMissing = true)
public class DoctorSeedData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DoctorSeedData.class);

    /** 30-minute slots, per §3.3. */
    private static final int SLOT_MINUTES = 30;

    private final DoctorRepository doctorRepository;
    private final DoctorAvailabilityRepository availabilityRepository;

    public DoctorSeedData(DoctorRepository doctorRepository, DoctorAvailabilityRepository availabilityRepository) {
        this.doctorRepository = doctorRepository;
        this.availabilityRepository = availabilityRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed("Dr. Tushar Mehta", "Orthopedic",
                List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                LocalTime.of(9, 0), LocalTime.of(12, 0));

        // A SECOND orthopedic doctor, and one who works weekends. Sample
        // conversation (iii) in docs/bot-requirements.md turns on both: it asks
        // the bot to list "the following orthopedic doctor(s)" as a choice, and
        // then has the caller switch from one to another for the SAME day when
        // the first isn't free. With a single orthopedic doctor, or with none
        // working today, neither half of that conversation can happen at all.
        seed("Dr. Anjali Desai", "Orthopedic",
                List.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.THURSDAY),
                LocalTime.of(10, 0), LocalTime.of(13, 0));

        seed("Dr. Priya Nair", "Cardiology",
                List.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
                LocalTime.of(10, 0), LocalTime.of(13, 0));

        // Shares Cardiology with Dr. Nair on purpose: it is what makes "that time
        // is taken, but another cardiologist is free then" reachable, which §6.2
        // asks the bot to offer and which cannot be tested with one doctor per
        // specialty.
        seed("Dr. Arjun Rao", "Cardiology",
                List.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
                LocalTime.of(14, 0), LocalTime.of(17, 0));

        seed("Dr. Meera Iyer", "Pediatrics",
                List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                LocalTime.of(9, 30), LocalTime.of(11, 30));

        seed("Dr. Sanjay Gupta", "General Medicine",
                List.of(DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY),
                LocalTime.of(11, 0), LocalTime.of(14, 0));
    }

    /**
     * Creates one doctor and expands their working window into individual
     * 30-minute pattern rows.
     *
     * <p>Keyed on name + specialty rather than name alone — the natural identity
     * of a row that has no other unique column. Adding a database unique
     * constraint instead would say two doctors can never share a name, which is
     * not true of doctors.
     */
    private void seed(String name, String specialty, List<DayOfWeek> days, LocalTime from, LocalTime to) {
        if (doctorRepository.existsByNameAndSpecialty(name, specialty)) {
            return;
        }

        Doctor doctor = doctorRepository.save(new Doctor(name, specialty));

        int slots = 0;
        for (DayOfWeek day : days) {
            for (LocalTime start = from; start.isBefore(to); start = start.plusMinutes(SLOT_MINUTES)) {
                availabilityRepository.save(
                        new DoctorAvailability(doctor.getId(), day, start, start.plusMinutes(SLOT_MINUTES)));
                slots++;
            }
        }

        log.info("Seeded doctor '{}' ({}) with {} weekly slots", name, specialty, slots);
    }
}
