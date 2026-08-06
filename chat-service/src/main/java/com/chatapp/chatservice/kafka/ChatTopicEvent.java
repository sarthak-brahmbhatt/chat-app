package com.chatapp.chatservice.kafka;

/**
 * Everything that can be published to the chat-messages topic. A sealed
 * interface, not a shared base class - ChatMessageConsumer.consume(...)
 * pattern-matches on it (Java 21 switch), the same dispatch-by-shape idea
 * ChatWebSocketHandler.routeAuthenticatedMessage already uses for WebSocket
 * envelopes, just enforced at compile time here instead of at a runtime
 * "type" field.
 *
 * Both permitted types share the SAME topic and, critically, the SAME
 * partition key derivation (ChatMessagePublisher.conversationKey) - that's
 * the entire mechanism CLAUDE.md 3.4/4's delivery-ordering fix relies on.
 * Kafka only guarantees ordering within one partition; putting both event
 * kinds for one conversation on that same partition is what makes "the
 * insert always happens before the delivery update" a structural guarantee
 * rather than a timing hope.
 */
public sealed interface ChatTopicEvent permits ChatMessageEvent, MessageDeliveredEvent {
}
