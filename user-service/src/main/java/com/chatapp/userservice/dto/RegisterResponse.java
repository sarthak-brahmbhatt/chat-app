package com.chatapp.userservice.dto;

/**
 * The success response body for POST /register (201 Created).
 *
 * Note what's NOT here: there is no password field at all — not plaintext,
 * not hashed. This is a separate class from User specifically so that's
 * structurally impossible to get wrong; there's no field to accidentally
 * serialize. Compare to just returning the User entity directly, where
 * someone could add a new field to User later and unknowingly expose it.
 */
public class RegisterResponse {

    private Long id;
    private String username;
    private String firstName;
    private String lastName;

    public RegisterResponse(Long id, String username, String firstName, String lastName) {
        this.id = id;
        this.username = username;
        this.firstName = firstName;
        this.lastName = lastName;
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
}
