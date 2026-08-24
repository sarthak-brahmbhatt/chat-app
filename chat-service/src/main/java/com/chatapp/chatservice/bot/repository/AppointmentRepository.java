package com.chatapp.chatservice.bot.repository;

import com.chatapp.chatservice.bot.entity.Appointment;
import com.chatapp.chatservice.bot.entity.AppointmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * The BACKWARD-LOOKING half of §4's two query directions.
 *
 * <p>Everything here reads {@code appointments} directly and applies NO
 * soft-delete filtering — not {@code doctors.active}, not
 * {@code doctor_availability.status}. That is correct, not an oversight:
 * BLOCKED and inactive mean "no new bookings from here on", never "cancel what
 * exists". An appointment booked before a doctor went on leave is still an
 * appointment the doctor turns up for, and it must still appear when asked what
 * is booked.
 *
 * <p>The forward-looking direction — "what can still be booked?" — is the
 * opposite, and lives entirely in DoctorAvailabilityRepository.findFreeSlots.
 * Applying this file's rules there would offer slots for absent doctors;
 * applying that file's rules here would make real appointments vanish.
 */
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    /**
     * Bookings inside the prompt-stuffing horizon (CLAUDE.md 3.9 §6.1) — the
     * "already taken" list the model subtracts the working pattern against.
     *
     * <p>Bounded to a horizon because this is Version 1's naive
     * everything-in-the-prompt approach and the bound is the only thing keeping
     * the prompt finite. That bound is also the feature: widening it is how the
     * token cost curve is made visible on purpose.
     */
    @Query("""
            SELECT ap FROM Appointment ap
            WHERE ap.status = :status
              AND ap.bookedForDate >= :from
              AND ap.bookedForDate <= :to
            ORDER BY ap.bookedForDate ASC, ap.startTime ASC
            """)
    List<Appointment> findInWindow(
            @Param("status") AppointmentStatus status,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * One patient's OWN bookings inside the window.
     *
     * <p>Deliberately a separate query from {@link #findInWindow}, feeding a
     * separately-labelled section of the prompt. They answer different
     * questions and conflating them caused a real cross-patient leak: the
     * prompt used to carry one unattributed list of every booked slot, and the
     * model — having no way to tell whose was whose — reported other patients'
     * appointments as the caller's own, in both directions.
     *
     * <p>Bounded to the same horizon as the availability data so the two
     * sections of the prompt describe the same stretch of calendar.
     */
    @Query("""
            SELECT ap FROM Appointment ap
            WHERE ap.userId = :userId
              AND ap.status = :status
              AND ap.bookedForDate >= :from
              AND ap.bookedForDate <= :to
            ORDER BY ap.bookedForDate ASC, ap.startTime ASC
            """)
    List<Appointment> findInWindowForUser(
            @Param("userId") Long userId,
            @Param("status") AppointmentStatus status,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    List<Appointment> findByUserIdOrderByBookedForDateAscStartTimeAsc(Long userId);
}
