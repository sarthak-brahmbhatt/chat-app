package com.chatapp.userservice.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The "DTO" (data transfer object) layer: the shape of the JSON body clients
 * send to POST /register. Deliberately a separate class from the entity
 * (User) — this is what's allowed to arrive over the wire, not what's stored.
 *
 * @NotBlank rejects null, empty string, AND whitespace-only values (stricter
 * than @NotNull, which would let "" or "   " through as "present"). It's only
 * applied to username, password, and firstName: per CLAUDE.md section 2 /
 * the clarified spec, lastName is collected but intentionally NOT part of the
 * required-field validation, so it has no constraint annotation here.
 *
 * These annotations do nothing by themselves — they're only enforced because
 * UserController's method parameter is annotated with @Valid, which tells
 * Spring to run this validation before the controller method body executes.
 */
public class RegisterRequest {

    @NotBlank(message = "username is required")
    private String username;

    @NotBlank(message = "password is required")
    private String password;

    @NotBlank(message = "firstName is required")
    private String firstName;

    private String lastName;

    // Jackson (the JSON library Spring Boot uses) needs a no-arg constructor to
    // deserialize the incoming request body, then populates fields via setters.
    public RegisterRequest() {
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }
}
