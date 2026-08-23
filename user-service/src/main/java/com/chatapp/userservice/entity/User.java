package com.chatapp.userservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * The "entity" layer: a plain Java class mapped 1:1 to a row in the User DB
 * (CLAUDE.md 3.5). This is what Hibernate reads/writes directly — it is NOT
 * what goes over the wire to clients. Keeping this separate from the request/
 * response DTOs (see the dto package) means the wire format (JSON in/out) and
 * the storage format (DB columns) can evolve independently, and means fields
 * like the hashed password never accidentally leak into an API response just
 * because they exist on this class.
 */
@Entity
// Table name is "users", not "user" — "user" is a reserved/ambiguous keyword in
// several SQL dialects (including MySQL in some contexts), so pluralizing sidesteps
// having to quote it everywhere.
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class User {

    @Id
    // IDENTITY delegates primary key generation to MySQL's AUTO_INCREMENT rather
    // than Hibernate managing a separate sequence/table — the simplest option and
    // the natural fit for MySQL specifically.
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // unique = true adds a DB-level unique constraint as a second line of defense
    // (redundant with the @Table uniqueConstraints above, and with the
    // existsByUsername() check in the service layer) so that even a race between
    // two concurrent registrations for the same username can't both succeed —
    // the second insert fails at the database.
    @Column(nullable = false, unique = true)
    private String username;

    // Holds the bcrypt HASH, never the plaintext password. Hashing happens in
    // UserService, before a User object is ever constructed — this field never
    // sees plaintext.
    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String firstName;

    // No `nullable = false` here: per the spec, lastName is collected at
    // registration but is not a required field, so the DB has to allow it to be
    // absent too.
    @Column
    private String lastName;

    // Human user or bot (CLAUDE.md 3.9, build-order step 18). chat-service reads
    // this exact column out of the now-shared chatappdb to decide whether an
    // outgoing message goes to a live WebSocket session or to the bot service —
    // it is the single flag the whole routing branch turns on.
    //
    // EnumType.STRING, not the default ORDINAL: ordinal stores 0/1, so inserting a
    // constant anywhere but the end of UserType would silently reinterpret every
    // existing row. The stored value is also then readable in a plain SQL client,
    // which matters for a column people will hand-query while debugging the bot.
    //
    // columnDefinition spells out the DDL rather than letting Hibernate infer it,
    // purely for the DEFAULT: Hibernate's ddl-auto:update would otherwise emit a
    // bare `not null` with no default, and that is the difference between this
    // column being addable to a table that already has rows and not.
    @Enumerated(EnumType.STRING)
    @Column(name = "user_type", nullable = false, length = 20,
            columnDefinition = "VARCHAR(20) NOT NULL DEFAULT 'USER'")
    private UserType userType = UserType.USER;

    // JPA requires a no-arg constructor (it builds the object via reflection, then
    // sets fields itself — no constructor args involved).
    protected User() {
    }

    /** Registers a normal human user — the only path {@code POST /register} uses. */
    public User(String username, String password, String firstName, String lastName) {
        this(username, password, firstName, lastName, UserType.USER);
    }

    /**
     * The explicit-type constructor. Separate from the 4-arg one above so that
     * creating a non-USER row is always a deliberate act at the call site
     * (currently only BotUserSeeder) rather than something an extra argument
     * could be passed by accident — registration cannot reach this overload
     * without naming the type it wants.
     */
    public User(String username, String password, String firstName, String lastName, UserType userType) {
        this.username = username;
        this.password = password;
        this.firstName = firstName;
        this.lastName = lastName;
        this.userType = userType;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public UserType getUserType() {
        return userType;
    }
}
