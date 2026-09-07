package com.chatapp.chatservice.bot.clinic.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * One slot in a doctor's RECURRING WEEKLY working pattern (CLAUDE.md 3.9 §3.3).
 *
 * <p>This table says nothing whatsoever about bookings. "Dr. Mehta works Mondays
 * 09:00–09:30" is a fact about the doctor's week that stays true whether or not
 * anyone has booked that Monday, and stays true across every Monday. What is
 * actually booked lives in {@link Appointment}, against a specific date.
 *
 * <p>Keeping those two things in separate tables is what makes the whole model
 * work: a recurring pattern cannot express "taken on the 24th but free on the
 * 31st", and a pure bookings table cannot express "the doctor does not work
 * Sundays". Free time is neither table on its own — it is the SUBTRACTION of one
 * from the other, computed in exactly one place (AvailabilityService).
 */
@Entity
@Table(
        name = "doctor_availability",
        // The availability query filters on (doctor, weekday) on every bot turn.
        // Trivial at five doctors; the index costs nothing and means the query
        // plan doesn't quietly become the reason a bigger seed set feels slow.
        indexes = @Index(name = "idx_availability_doctor_day", columnList = "doctor_id, day_of_week"))
public class DoctorAvailability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // A plain FK column rather than a @ManyToOne association, here and in
    // Appointment. The queries that matter join explicitly in JPQL and project
    // straight into records, so an association would buy nothing but lazy-loading
    // hazards — this service runs with open-in-view:false, where a lazy proxy
    // touched outside its transaction fails at runtime rather than at compile time.
    @Column(name = "doctor_id", nullable = false)
    private Long doctorId;

    // java.time.DayOfWeek, persisted as its name — MONDAY..SUNDAY, exactly the
    // schema's VARCHAR(10). Using the JDK enum rather than a String means
    // "does this date fall on this row's weekday?" is a type-safe comparison
    // against LocalDate.getDayOfWeek() instead of string handling, which is
    // precisely the check §6.4 needs against a hallucinated date.
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 10)
    private DayOfWeek dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AvailabilityStatus status = AvailabilityStatus.AVAILABLE;

    protected DoctorAvailability() {
    }

    public DoctorAvailability(Long doctorId, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime) {
        this.doctorId = doctorId;
        this.dayOfWeek = dayOfWeek;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = AvailabilityStatus.AVAILABLE;
    }

    public Long getId() {
        return id;
    }

    public Long getDoctorId() {
        return doctorId;
    }

    public DayOfWeek getDayOfWeek() {
        return dayOfWeek;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public AvailabilityStatus getStatus() {
        return status;
    }
}
