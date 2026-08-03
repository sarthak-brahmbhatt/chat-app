package com.chatapp.userservice.dto;

import java.util.List;

/**
 * The response body for GET /users.
 *
 * message is always populated — "3 user(s) found." or, per the requirement,
 * something more informative than a bare empty array when there are none:
 * "No registered users found." Always including it (rather than only when
 * the list is empty) keeps the response shape consistent regardless of data
 * state, so client code never has to branch on "is message present."
 */
public class UserListResponse {

    private List<UserSummary> users;
    private String message;

    public UserListResponse(List<UserSummary> users, String message) {
        this.users = users;
        this.message = message;
    }

    public List<UserSummary> getUsers() {
        return users;
    }

    public String getMessage() {
        return message;
    }
}
