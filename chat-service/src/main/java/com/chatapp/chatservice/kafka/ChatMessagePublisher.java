package com.chatapp.chatservice.kafka;

import com.chatapp.chatservice.dto.ChatMessageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Publishes every routed chat message to the chat-messages topic — the
 * producer side of CLAUDE.md 3.4's Kafka-backed async persistence.
 * ChatWebSocketHandler calls publish() for EVERY message it routes
 * (whether or not the recipient is currently connected — this happens
 * independently of, not conditionally on, live-delivery success).
 *
 * Non-blocking by construction: publish() never waits for Kafka to actually
 * acknowledge the record before returning. KafkaTemplate.send() is
 * asynchronous — it hands the record to the producer's internal buffer and
 * returns a CompletableFuture immediately; the actual network round trip to
 * the broker happens on a background thread. We only ever attach
 * .whenComplete(...) for logging, and deliberately never call .get()/.join()
 * on that future — doing so would block the calling thread (the same
 * WebSocket I/O thread that just sent the single-tick ack and is about to
 * attempt live delivery) until Kafka responds, which is exactly the
 * coupling this architecture exists to avoid.
 */
@Component
public class ChatMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(ChatMessagePublisher.class);

    // Widened from KafkaTemplate<String, ChatMessageEvent>: this producer
    // now sends TWO event kinds to the same topic (ChatMessageEvent and
    // MessageDeliveredEvent, see ChatTopicEvent) so the delivery-ordering
    // fix in publishDelivered can share a partition key with the original
    // message. Spring Boot's auto-configured KafkaTemplate bean resolves
    // here regardless of the declared generic (no new @Bean needed); no
    // spring.json.value.default.type is configured, so JsonDeserializer on
    // the consumer side already resolves each record's concrete type from
    // JsonSerializer's own __TypeId__ header - the standard Spring Kafka
    // mechanism for a multi-type topic, not new configuration surface.
    private final KafkaTemplate<String, ChatTopicEvent> kafkaTemplate;

    public ChatMessagePublisher(KafkaTemplate<String, ChatTopicEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * sentAt is minted ONCE by the caller (ChatWebSocketHandler.handleChatMessage)
     * and passed in here, rather than this method calling Instant.now() itself
     * - the same Instant also goes into the live incoming_message envelope
     * (IncomingChatMessage), so the persisted timestamp and the one shown on
     * the recipient's live-delivered bubble for the same message are always
     * identical, not two independent clock reads a few milliseconds apart.
     */
    public void publish(String senderId, ChatMessageRequest request, Instant sentAt) {
        try {
            String key = conversationKey(senderId, request.recipientId());
            ChatMessageEvent event = new ChatMessageEvent(
                    request.messageId(), senderId, request.recipientId(), request.content(), sentAt);

            kafkaTemplate.send(KafkaTopicConfig.CHAT_MESSAGES_TOPIC, key, event)
                    .whenComplete((result, exception) -> {
                        if (exception != null) {
                            log.warn("Failed to publish message {} to Kafka: {}", request.messageId(), exception.getMessage());
                        } else {
                            log.debug("Published message {} to partition {}",
                                    request.messageId(), result.getRecordMetadata().partition());
                        }
                    });
        } catch (RuntimeException e) {
            // kafkaTemplate.send() can itself throw SYNCHRONOUSLY in some
            // failure modes (e.g. a serialization error, or the producer's
            // local send buffer staying full past its configured wait time)
            // rather than only failing the returned future asynchronously.
            // Either way, a Kafka problem must never propagate out of this
            // method: per CLAUDE.md 3.4, the sender's single tick — already
            // sent by the time this runs, see ChatWebSocketHandler — must
            // never be put at risk by Kafka being slow, unreachable, or
            // otherwise misbehaving.
            log.warn("Failed to publish message {} to Kafka: {}", request.messageId(), e.getMessage());
        }
    }

    /**
     * Publishes a MessageDeliveredEvent for messageId, keyed by the SAME
     * conversationKey(senderId, recipientId) as the original ChatMessageEvent
     * for this message — the entire mechanism behind CLAUDE.md 4's
     * delivery-ordering fix. Kafka guarantees ordering only within one
     * partition; using the identical key guarantees this event lands on the
     * SAME partition the original message did, and a delivered_ack can only
     * ever be produced after the message it acknowledges was already
     * produced — so ChatMessageConsumer is structurally guaranteed to
     * process the insert before this delivery event, every time, not just
     * usually. Called from ChatWebSocketHandler.handleDeliveredAck; same
     * non-blocking, exception-swallowing shape as publish() above, for the
     * same reason — a Kafka hiccup here must never propagate back into the
     * live delivered_ack handling path.
     */
    public void publishDelivered(String senderId, String recipientId, String messageId) {
        try {
            String key = conversationKey(senderId, recipientId);
            MessageDeliveredEvent event = new MessageDeliveredEvent(messageId);

            kafkaTemplate.send(KafkaTopicConfig.CHAT_MESSAGES_TOPIC, key, event)
                    .whenComplete((result, exception) -> {
                        if (exception != null) {
                            log.warn("Failed to publish delivery for message {} to Kafka: {}", messageId, exception.getMessage());
                        } else {
                            log.debug("Published delivery for message {} to partition {}",
                                    messageId, result.getRecordMetadata().partition());
                        }
                    });
        } catch (RuntimeException e) {
            log.warn("Failed to publish delivery for message {} to Kafka: {}", messageId, e.getMessage());
        }
    }

    /**
     * A canonical, ORDER-INDEPENDENT key for the two-person conversation
     * between these two ids — the smaller id always comes first,
     * regardless of who's the sender and who's the recipient. This is what
     * makes Kafka's per-partition ordering guarantee actually cover a whole
     * conversation: Kafka only guarantees order WITHIN a partition, and the
     * partition a record lands on is derived from its key
     * (hash(key) % partitionCount). If we keyed by senderId alone, Alice's
     * messages to Bob and Bob's messages to Alice — two directions of the
     * SAME conversation — could hash to different partitions, with no
     * ordering guarantee between them; a fast reply could then be persisted
     * before the message it was replying to. Keying by this canonical pair
     * instead guarantees every message either person sends to the other
     * lands on the same partition, so the send-order of the conversation as
     * a whole is preserved. Different conversations (a different pair of
     * ids) are free to land on different partitions and be processed in any
     * order relative to each other — nothing requires that, and forcing it
     * would kill the parallelism partitioning exists for, with no benefit.
     */
    static String conversationKey(String senderId, String recipientId) {
        return senderId.compareTo(recipientId) < 0
                ? senderId + ":" + recipientId
                : recipientId + ":" + senderId;
    }
}
