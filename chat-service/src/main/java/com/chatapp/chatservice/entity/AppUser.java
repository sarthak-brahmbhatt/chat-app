package com.chatapp.chatservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A READ-ONLY window onto the {@code users} table (CLAUDE.md 3.5/3.9).
 *
 * <p>chat-service could not see this table at all before the chatappdb
 * consolidation — `users` lived in userdb and messages in messagedb, so
 * "is this recipient a bot?" was not a question that could be asked in SQL. It
 * is the one genuinely new cross-table reach the consolidation buys, and it is
 * why the consolidation happened.
 *
 * <p><b>chat-service never writes here.</b> user-service owns this table and
 * remains its only writer (CLAUDE.md 3.2's service boundary survives at the code
 * level even though the database no longer enforces it). Nothing in the type
 * system stops a write — there is no read-only mapping in JPA that would — so
 * this is a convention held by the absence of any save() call, and by
 * AppUserRepository exposing only lookups.
 *
 * <p>Named AppUser, not User: "User" would collide confusingly with
 * user-service's own entity of that name in every discussion of this code, and
 * this class is deliberately NOT that class — it maps four of its columns and
 * pointedly not `password`.
 *
 * <p>Only the columns chat-service actually needs are mapped. Hibernate's
 * ddl-auto:update never drops columns it doesn't know about, so a partial
 * mapping over a table another service created is safe — but the reverse is
 * not, which is why docker-compose.yml makes chat-service wait for
 * user-service's healthcheck rather than racing it to create the table.
 */
@Entity
@Table(name = "users")
public class AppUser {

    // No @GeneratedValue: this class never inserts, so there is no generation
    // strategy for Hibernate to apply. The value is always read from a row
    // user-service already wrote.
    @Id
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String firstName;

    @Column
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_type", nullable = false, length = 20)
    private UserType userType;

    protected AppUser() {
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
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

    public boolean isBot() {
        return userType == UserType.BOT;
    }
}
