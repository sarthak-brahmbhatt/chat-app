package com.chatapp.chatservice.bot.clinic;

import com.chatapp.chatservice.bot.clinic.entity.AppointmentStatus;
import com.chatapp.chatservice.bot.clinic.entity.AvailabilityStatus;
import com.chatapp.chatservice.bot.clinic.repository.DoctorAvailabilityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * <b>The one place free time is computed</b> (CLAUDE.md 3.9 §4).
 *
 * <p>Free slots are derived by subtraction and never stored as a flag:
 *
 * <pre>
 *   free(doctor, date) = availability rows whose day_of_week matches date's weekday
 *                        AND status = AVAILABLE
 *                        AND doctors.active = TRUE
 *                        MINUS appointments for that doctor on date with status = BOOKED
 * </pre>
 *
 * <p>A stored "is free" flag would be wrong within one line of writing it: the
 * same pattern row is free on one Monday and taken on the next, so freeness is a
 * property of (slot, date), not of the slot. There is nowhere to put the flag.
 *
 * <p>Both callers route through here — prompt building and the pre-insert
 * re-check — and both reach the same query underneath, so there is exactly one
 * definition of "bookable" in the service. §4 asks for that explicitly, because
 * the realistic second implementation forgets {@code doctors.active} and reads
 * as obviously correct while quietly offering appointments with a doctor who is
 * on leave.
 */
@Service
public class AvailabilityService {

    private final DoctorAvailabilityRepository availabilityRepository;

    public AvailabilityService(DoctorAvailabilityRepository availabilityRepository) {
        this.availabilityRepository = availabilityRepository;
    }

    /**
     * Everything genuinely bookable on {@code date}, across all active doctors.
     *
     * <p>The weekday is derived here from the date rather than accepted as an
     * argument. They are two views of one input, and letting a caller supply both
     * would let them disagree — a query for "Monday's slots, on the 25th" would
     * return the pattern for a day the date isn't, and every one of those slots
     * would look bookable.
     */
    @Transactional(readOnly = true)
    public List<FreeSlot> freeSlotsOn(LocalDate date) {
        return availabilityRepository.findFreeSlots(
                date,
                date.getDayOfWeek(),
                AvailabilityStatus.AVAILABLE,
                AppointmentStatus.BOOKED);
    }

    /**
     * The specific slot {@code availabilityId} on {@code date}, if it is free.
     *
     * <p>Implemented by filtering {@link #freeSlotsOn} rather than by a second,
     * narrower query. A dedicated {@code WHERE a.id = :id} version would be a
     * little faster and would be the second copy of the subtraction that §4
     * warns about — the two would then have to be kept in step by hand forever,
     * to save a filter over the handful of rows one clinic day has.
     *
     * <p>An empty result deliberately does not distinguish WHY: no such slot, a
     * blocked slot, an inactive doctor, a slot on a different weekday than this
     * date, or one already taken. The caller's only decision is book or don't,
     * and every one of those answers is don't.
     */
    @Transactional(readOnly = true)
    public Optional<FreeSlot> findFreeSlot(long availabilityId, LocalDate date) {
        return freeSlotsOn(date).stream()
                .filter(slot -> slot.availabilityId() == availabilityId)
                .findFirst();
    }
}
