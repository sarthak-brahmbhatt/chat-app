package com.chatapp.chatservice.kafka;

import java.time.Instant;

/**
 * The Kafka message VALUE published to the chat-messages topic — the
 * payload ChatMessagePublisher produces and ChatMessageConsumer consumes.
 * Deliberately its own type, not a reuse of ChatMessageRequest (the
 * WebSocket wire DTO) or ChatMessage (the JPA entity): this represents a
 * third, distinct contract — "what crosses the Kafka topic" — which happens
 * to overlap in fields with both right now but is free to diverge from
 * either (e.g. a future event schema version, or fields the DB doesn't
 * need) without dragging the WebSocket protocol or the DB schema along
 * with it.
 *
 * sentAt is captured here (at publish time), not left for the consumer to
 * fill in at persist time — the consumer could run noticeably later than
 * the message actually happened (consumer lag, a restart, at-least-once
 * redelivery), and "when was this message actually sent" should reflect
 * that original moment, not whenever it happened to get persisted.
 */
public record ChatMessageEvent(String messageId, String senderId, String recipientId, String content, Instant sentAt)
        implements ChatTopicEvent {
}
