package com.chatapp.chatservice.bot.promptstuffing;

import com.chatapp.chatservice.bot.clinic.entity.AppointmentStatus;
import com.chatapp.chatservice.bot.clinic.entity.AvailabilityStatus;
import com.chatapp.chatservice.bot.clinic.repository.AppointmentRepository;
import com.chatapp.chatservice.bot.clinic.repository.DoctorAvailabilityRepository;
import com.chatapp.chatservice.bot.clinic.repository.DoctorRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Reads the four things §6.1 says go into every prompt (CLAUDE.md 3.9).
 *
 * <p>Queried on every turn, deliberately uncached — see {@link ClinicSnapshot}
 * for why staleness here would be a correctness bug rather than a performance
 * trade.
 */
@Service
public class ClinicDataProvider {

    private final DoctorRepository doctorRepository;
    private final DoctorAvailabilityRepository availabilityRepository;
    private final AppointmentRepository appointmentRepository;
    private final Clock clock;
    private final int horizonDays;

    public ClinicDataProvider(
            DoctorRepository doctorRepository,
            DoctorAvailabilityRepository availabilityRepository,
            AppointmentRepository appointmentRepository,
            Clock clock,
            @Value("${bot.booking-horizon-days}") int horizonDays) {
        this.doctorRepository = doctorRepository;
        this.availabilityRepository = availabilityRepository;
        this.appointmentRepository = appointmentRepository;
        this.clock = clock;
        this.horizonDays = horizonDays;
    }

    /**
     * @param currentUserId whose conversation this is. Needed because the
     *                      snapshot is no longer the same for every caller —
     *                      it carries their own appointments as a distinct,
     *                      separately-labelled list.
     */
    @Transactional(readOnly = true)
    public ClinicSnapshot snapshot(long currentUserId) {
        LocalDate today = LocalDate.now(clock);
        LocalTime now = LocalTime.now(clock);

        // Inclusive of today, so a horizon of 7 means today plus the next six
        // days — "the next 7 days" as a person means it, not eight dates.
        LocalDate horizonEnd = today.plusDays(horizonDays - 1L);

        return new ClinicSnapshot(
                today,
                now,
                doctorRepository.findActiveSpecialties(),
                doctorRepository.findByActiveTrueOrderByNameAsc(),
                availabilityRepository.findActivePattern(AvailabilityStatus.AVAILABLE),
                // Bounded to the horizon on both ends. The lower bound matters as
                // much as the upper one: past bookings cannot affect what is
                // still bookable, so including them would spend tokens on rows
                // that can only distract, and would grow without limit.
                appointmentRepository.findInWindow(AppointmentStatus.BOOKED, today, horizonEnd),
                // The caller's own, read separately and rendered under its own
                // heading. The list above is availability data and carries no
                // owner; this one is the only thing the bot may call "yours".
                appointmentRepository.findInWindowForUser(
                        currentUserId, AppointmentStatus.BOOKED, today, horizonEnd),
                horizonEnd);
    }
}
