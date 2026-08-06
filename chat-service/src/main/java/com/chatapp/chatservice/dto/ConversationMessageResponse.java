package com.chatapp.chatservice.dto;

import com.chatapp.chatservice.entity.ChatMessage;

import java.time.Instant;

/**
 * One message in a GET /conversations/{otherUserId}/messages response body.
 *
 * Deliberately its own record, not ChatMessage (the JPA entity) serialized
 * straight back out over HTTP — same layering reason DTOs exist everywhere
 * else in this project (e.g. UserSummary vs. User): the wire shape and the
 * persistence shape are allowed to diverge, and here they already do.
 * `delivered` reads naturally as "was this delivered" to a frontend that
 * only needs to know single-tick vs. double-tick; the entity's own
 * `delivered` field is what backs it, but exposing the entity directly
 * would also leak the internal `id` primary key, which the wire protocol
 * has never used anywhere (messageId, the client-generated correlation id,
 * is what both this response and the live WebSocket protocol key on).
 */
public record ConversationMessageResponse(
        String messageId,
        String senderId,
        String recipientId,
        String content,
        Instant sentAt,
        boolean delivered) {

    public static ConversationMessageResponse from(ChatMessage message) {
        return new ConversationMessageResponse(
                message.getMessageId(),
                message.getSenderId(),
                message.getRecipientId(),
                message.getContent(),
                message.getSentAt(),
                message.isDelivered());
    }
}
