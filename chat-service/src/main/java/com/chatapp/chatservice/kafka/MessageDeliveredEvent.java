package com.chatapp.chatservice.kafka;

/**
 * Published by ChatMessagePublisher.publishDelivered when a delivered_ack
 * arrives (ChatWebSocketHandler.handleDeliveredAck), keyed by the SAME
 * conversationKey as the original ChatMessageEvent for this messageId - see
 * ChatTopicEvent's class comment for why that's the whole point. Minimal on
 * purpose: ChatMessageConsumer only ever needs the id to run the existing
 * ChatMessageRepository.markDelivered bulk update.
 */
public record MessageDeliveredEvent(String messageId) implements ChatTopicEvent {
}
