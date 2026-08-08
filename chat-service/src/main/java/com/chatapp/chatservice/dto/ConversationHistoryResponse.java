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
 *
 * `hasMore` tells the caller whether an older page might still exist, so the
 * frontend's scroll-to-top pagination knows when to stop trying. Computed
 * cheaply off whether a full page was returned (see
 * ChatMessageService.getConversationHistory) rather than a separate COUNT
 * query - the accepted imprecision (a conversation with EXACTLY one full
 * page of older messages left reports hasMore=true, costing one wasted
 * empty fetch next scroll) is cheaper than a second query on every request
 * to avoid a single harmless extra round trip.
 */
public class ConversationHistoryResponse {

    private final List<ConversationMessageResponse> messages;
    private final String message;
    private final boolean hasMore;

    public ConversationHistoryResponse(List<ConversationMessageResponse> messages, String message, boolean hasMore) {
        this.messages = messages;
        this.message = message;
        this.hasMore = hasMore;
    }

    public List<ConversationMessageResponse> getMessages() {
        return messages;
    }

    public String getMessage() {
        return message;
    }

    public boolean isHasMore() {
        return hasMore;
    }
}
