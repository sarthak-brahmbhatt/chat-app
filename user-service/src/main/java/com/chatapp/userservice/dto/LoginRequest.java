package com.chatapp.userservice.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The JSON body shape for POST /login. Structurally the same idea as
 * RegisterRequest: @NotBlank on both fields catches a missing/empty username
 * or password before AuthService ever runs, via the same @Valid +
 * GlobalExceptionHandler mechanism used by /register.
 *
 * This is a genuinely different concern from the "don't reveal which one
 * specifically" requirement on wrong credentials: "you submitted an empty
 * username field" reveals nothing about which usernames exist in the system,
 * so returning a plain 400 here doesn't undermine that protection. That
 * protection only applies once we're actually checking a submitted value
 * against the database — see AuthService.
 */
public class LoginRequest {

    @NotBlank(message = "username is required")
    private String username;

    @NotBlank(message = "password is required")
    private String password;

    public LoginRequest() {
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
}
