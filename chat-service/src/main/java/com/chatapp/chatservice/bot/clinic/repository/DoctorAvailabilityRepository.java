package com.chatapp.chatservice.bot.clinic.repository;

import com.chatapp.chatservice.bot.clinic.FreeSlot;
import com.chatapp.chatservice.bot.clinic.entity.AppointmentStatus;
import com.chatapp.chatservice.bot.clinic.entity.AvailabilityStatus;
import com.chatapp.chatservice.bot.clinic.entity.DoctorAvailability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

public interface DoctorAvailabilityRepository extends JpaRepository<DoctorAvailability, Long> {

    /**
     * §4's availability subtraction, as one query. <b>This is the only place it is
     * expressed.</b> Everything that needs to know whether a slot is bookable —
     * prompt building, and the pre-insert re-check in BookingService — goes
     * through {@code AvailabilityService}, which goes through here.
     *
     * <p>That single-definition rule is not tidiness. §4 calls out the specific
     * failure it prevents: a second, hand-written version of this query that
     * subtracts booked appointments but forgets {@code doctors.active} looks
     * completely correct and silently offers slots with a doctor who is on leave.
     * There is no test that naturally catches it and no symptom until a patient
     * turns up.
     *
     * <p>Every clause below is load-bearing:
     * <ul>
     *   <li>{@code d.active = true} — the doctor still takes new bookings at all.
     *   <li>{@code a.status = AVAILABLE} — this particular slot is not blocked.
     *   <li>{@code a.dayOfWeek = :dayOfWeek} — the recurring pattern covers the
     *       weekday the requested date actually falls on. The caller derives this
     *       from {@code :date}; they are two views of one input and must agree,
     *       which is why callers do not get to pass them independently.
     *   <li>the {@code NOT EXISTS} — nothing is already booked against this slot
     *       on this date. Scoped to {@code BOOKED} rather than "any row", so a
     *       future cancellation frees the slot by changing a status.
     * </ul>
     *
     * <p>Both soft-delete filters are FORWARD-LOOKING only, and that asymmetry is
     * deliberate: a blocked slot or a deactivated doctor stops NEW bookings and
     * never cancels existing ones. Reading back what is already booked must
     * therefore ignore both filters entirely and read {@code appointments}
     * directly — see AppointmentRepository. Confusing the two directions is how a
     * patient with a real, honoured appointment gets told they have none.
     *
     * <p>This is also, concretely, the join the chatappdb consolidation was for:
     * three tables in one statement, which the old two-database split could not
     * have expressed in SQL at all.
     */
    @Query("""
            SELECT new com.chatapp.chatservice.bot.clinic.FreeSlot(
                       a.id, d.id, d.name, d.specialty, :date, a.startTime, a.endTime)
            FROM DoctorAvailability a
            JOIN Doctor d ON d.id = a.doctorId
            WHERE d.active = true
              AND a.status = :available
              AND a.dayOfWeek = :dayOfWeek
              AND NOT EXISTS (
                  SELECT 1 FROM Appointment ap
                  WHERE ap.availabilityId = a.id
                    AND ap.bookedForDate = :date
                    AND ap.status = :booked
              )
            ORDER BY d.name ASC, a.startTime ASC
            """)
    List<FreeSlot> findFreeSlots(
            @Param("date") LocalDate date,
            @Param("dayOfWeek") DayOfWeek dayOfWeek,
            @Param("available") AvailabilityStatus available,
            @Param("booked") AppointmentStatus booked);

    /**
     * The raw recurring pattern for active doctors, with no subtraction at all —
     * what §6.1 injects into the prompt as "the doctors' working hours".
     *
     * <p>Not a substitute for {@link #findFreeSlots}: this says when a doctor
     * WORKS, not when they are free. The model is given this plus the list of
     * existing bookings and left to do the subtraction itself, which is exactly
     * the naive Version 1 approach — and exactly why the model's answer is never
     * trusted, only its intent. {@link #findFreeSlots} is what actually decides.
     */
    @Query("""
            SELECT a FROM DoctorAvailability a
            JOIN Doctor d ON d.id = a.doctorId
            WHERE d.active = true
              AND a.status = :available
            ORDER BY d.name ASC, a.dayOfWeek ASC, a.startTime ASC
            """)
    List<DoctorAvailability> findActivePattern(@Param("available") AvailabilityStatus available);

    List<DoctorAvailability> findByDoctorId(Long doctorId);
}
