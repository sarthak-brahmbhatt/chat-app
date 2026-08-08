package com.chatapp.chatservice.dto;

import java.time.Instant;

/**
 * The server → recipient envelope for a live-delivered message (CLAUDE.md
 * 3.1): {"type":"incoming_message","messageId":"...","senderId":"...","content":"...","sentAt":"..."}
 *
 * Deliberately a different `type` than ChatMessageRequest's "message", even
 * though the payload is nearly identical (recipientId swapped for senderId):
 * a client should be able to dispatch purely on `type`, never by inferring
 * direction from which fields happen to be present.
 *
 * sentAt is passed in by the caller rather than stamped here with
 * Instant.now() - ChatWebSocketHandler mints exactly one Instant per message
 * and shares it with both this envelope and ChatMessagePublisher's Kafka
 * event, so the live-delivered timestamp and the persisted one for the same
 * message are always identical, not two independent clock reads a few
 * milliseconds apart.
 */
public record IncomingChatMessage(String type, String messageId, String senderId, String content, Instant sentAt) {

    public static IncomingChatMessage from(String senderId, ChatMessageRequest request, Instant sentAt) {
        return new IncomingChatMessage("incoming_message", request.messageId(), senderId, request.content(), sentAt);
    }
}
