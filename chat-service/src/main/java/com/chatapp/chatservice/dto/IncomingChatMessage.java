package com.chatapp.chatservice.dto;

/**
 * The server → recipient envelope for a live-delivered message (CLAUDE.md
 * 3.1): {"type":"incoming_message","messageId":"...","senderId":"...","content":"..."}
 *
 * Deliberately a different `type` than ChatMessageRequest's "message", even
 * though the payload is nearly identical (recipientId swapped for senderId):
 * a client should be able to dispatch purely on `type`, never by inferring
 * direction from which fields happen to be present.
 */
public record IncomingChatMessage(String type, String messageId, String senderId, String content) {

    public static IncomingChatMessage from(String senderId, ChatMessageRequest request) {
        return new IncomingChatMessage("incoming_message", request.messageId(), senderId, request.content());
    }
}
