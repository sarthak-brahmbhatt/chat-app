package com.chatapp.userservice.dto;

/**
 * One entry in the GET /users listing. Deliberately a separate class from
 * RegisterResponse, even though the fields are identical today: they
 * represent different API contracts (RegisterResponse is "confirmation of
 * the account you just created"; this is "a summary of some other user, as
 * shown in a list") that happen to coincide right now but are free to
 * diverge later — e.g. a listing might reasonably choose to show less detail
 * about other users than you get back about yourself at registration.
 * Reusing RegisterResponse here would tie those two unrelated decisions
 * together for no real benefit.
 *
 * No password field, for the same structural reason as RegisterResponse:
 * there's no field to accidentally serialize.
 */
public class UserSummary {

    private Long id;
    private String username;
    private String firstName;
    private String lastName;

    public UserSummary(Long id, String username, String firstName, String lastName) {
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
