package com.chatapp.chatservice.bot.clinic.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A doctor at the clinic — pure reference data (CLAUDE.md 3.9 §3.2).
 *
 * <p>Deliberately NOT a {@code users} row and NOT a UserType: doctors never log
 * in and never chat in Version 1, so giving them an account would model a
 * relationship the product doesn't have. DOCTOR as a user type is explicitly out
 * of scope.
 */
@Entity
@Table(name = "doctors")
public class Doctor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    // Free text rather than an enum, and that is load-bearing: the system prompt
    // injects the DISTINCT specialties actually present in this column, and tells
    // the model to use only those. An enum would put the authoritative list in
    // Java, where adding a specialty means a redeploy; keeping it in the data
    // means seeding a doctor is the whole operation, and "sorry, we have no
    // dermatologist" stays true automatically.
    @Column(nullable = false, length = 100)
    private String specialty;

    // Soft delete: FALSE means "no new bookings", never "cancel what exists" and
    // never "remove the row" — historical appointments still reference it. See
    // AvailabilityStatus.BLOCKED for the same semantics one level down.
    @Column(nullable = false)
    private boolean active = true;

    protected Doctor() {
    }

    public Doctor(String name, String specialty) {
        this.name = name;
        this.specialty = specialty;
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSpecialty() {
        return specialty;
    }

    public boolean isActive() {
        return active;
    }
}
