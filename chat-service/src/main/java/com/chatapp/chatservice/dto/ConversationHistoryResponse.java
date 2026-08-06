package com.chatapp.chatservice.dto;

import java.util.List;

/**
 * The response body for GET /conversations/{otherUserId}/messages.
 *
 * Mirrors user-service's UserListResponse shape deliberately (a data list
 * plus an always-populated `message` field) rather than returning a bare
 * JSON array - same reasoning: "appropriate message if none exist" is an
 * explicit requirement here too (an empty chat history is a normal, first-
 * time-conversation state, not an error), and always including the message
 * field regardless of whether the list is empty keeps the response shape
 * uniform - client code never has to branch on "is message present."
 */
public class ConversationHistoryResponse {

    private final List<ConversationMessageResponse> messages;
    private final String message;

    public ConversationHistoryResponse(List<ConversationMessageResponse> messages, String message) {
        this.messages = messages;
        this.message = message;
    }

    public List<ConversationMessageResponse> getMessages() {
        return messages;
    }

    public String getMessage() {
        return message;
    }
}
