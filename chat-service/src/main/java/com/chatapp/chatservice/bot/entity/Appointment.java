package com.chatapp.chatservice.bot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * An actual booking, on a specific calendar date (CLAUDE.md 3.9 §3.4).
 *
 * <p>The counterpart to {@link DoctorAvailability}: that table is the recurring
 * weekly pattern, this one is what has actually been taken out of it.
 *
 * <p><b>The unique constraint on (availability_id, booked_for_date)</b> is the
 * last line of defence behind BookingService's re-check. That re-check reads,
 * then writes, and two users can interleave between the two — §6.4 names this
 * exact race ("someone may have booked it seconds ago") and a re-read cannot
 * close it on its own. The database can.
 *
 * <p>It is unconditional, which is only correct because cancellation is out of
 * scope: once a CANCELLED_BY_* row can exist, this constraint would keep a
 * cancelled slot permanently unbookable, since it counts rows of any status.
 * MySQL has no partial/filtered unique index to express "at most one BOOKED
 * row", so whoever adds cancellation has to replace this with something else —
 * a nullable generated column that is NULL unless the status is BOOKED is the
 * usual trick. Failing loudly on a duplicate today is worth more than leaving
 * the race open for a feature that does not exist yet.
 */
@Entity
@Table(
        name = "appointments",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_appointment_slot",
                columnNames = {"availability_id", "booked_for_date"}),
        indexes = {
                @Index(name = "idx_appointment_doctor_date", columnList = "doctor_id, booked_for_date"),
                @Index(name = "idx_appointment_user", columnList = "user_id")
        })
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doctor_id", nullable = false)
    private Long doctorId;

    @Column(name = "availability_id", nullable = false)
    private Long availabilityId;

    // Must land on the weekday named by the referenced availability row's
    // day_of_week. Nothing in the schema can express that, so BookingService
    // checks it explicitly before every insert — it is exactly the constraint a
    // model that hallucinates a date will violate.
    @Column(name = "booked_for_date", nullable = false)
    private LocalDate bookedForDate;

    // Copied from the availability row rather than read through it, deliberately.
    // A doctor who later changes their recurring pattern must not retroactively
    // rewrite what time an existing appointment was booked FOR — the patient was
    // told 09:00, and 09:00 is what the record has to keep saying. Normalising
    // this away would make historical appointments silently follow the pattern.
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AppointmentStatus status = AppointmentStatus.BOOKED;

    // users.id. A Long, while chat-service handles user ids as Strings everywhere
    // else — those come from the JWT "sub" claim, which user-service builds with
    // String.valueOf(userId) (see frontend/src/app/models/chat.models.ts, which
    // documents the same seam from the other side). This column is a real foreign
    // key into a real table, so it is typed as the table types it, and the
    // conversion happens once, at the boundary in BookingService.
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "booked_on", nullable = false)
    private Instant bookedOn;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Appointment() {
    }

    public Appointment(
            Long doctorId,
            Long availabilityId,
            LocalDate bookedForDate,
            LocalTime startTime,
            LocalTime endTime,
            Long userId,
            Instant bookedOn) {
        this.doctorId = doctorId;
        this.availabilityId = availabilityId;
        this.bookedForDate = bookedForDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.userId = userId;
        this.status = AppointmentStatus.BOOKED;
        this.bookedOn = bookedOn;
        this.updatedAt = bookedOn;
    }

    public Long getId() {
        return id;
    }

    public Long getDoctorId() {
        return doctorId;
    }

    public Long getAvailabilityId() {
        return availabilityId;
    }

    public LocalDate getBookedForDate() {
        return bookedForDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public AppointmentStatus getStatus() {
        return status;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getBookedOn() {
        return bookedOn;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
