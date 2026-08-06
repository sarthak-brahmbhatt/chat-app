package com.chatapp.chatservice.kafka;

import com.chatapp.chatservice.entity.ChatMessage;
import com.chatapp.chatservice.repository.ChatMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The consumer side of CLAUDE.md 3.4's Kafka-backed async persistence —
 * embedded directly in chat-service (the same deployable that runs
 * ChatWebSocketHandler and ChatMessagePublisher), not a separate service.
 *
 * The tradeoff, made explicit: persistence (this class) is logically a
 * different job from handling live WebSocket connections — a SEPARATE
 * service dedicated to consuming and persisting could scale independently
 * of WebSocket connection capacity, and wouldn't need every chat-service
 * instance to also run a consumer. The cost of doing that now would be
 * real: a new Gradle project, its own Dockerfile, its own docker-compose
 * entry, its own JVM footprint — meaningful infrastructure for what's
 * currently one @KafkaListener method. This project has repeatedly made
 * this same call elsewhere (e.g. not extracting a shared JWT-verification
 * module in build-order step 5) — keep the smallest reasonable deployable
 * until there's a concrete reason to split it. If persistence throughput
 * ever needs to scale independently of WebSocket connection count, THAT is
 * the trigger to extract this into its own service, not a decision to
 * pre-empt now.
 *
 * @KafkaListener is the consumer-side counterpart to KafkaTemplate: Spring
 * subscribes this method to the named topic under the given consumer group
 * and invokes it once per record received, handling polling/deserialization/
 * offset-commit bookkeeping so this method only has to deal with "given one
 * event, what do I do with it."
 */
@Component
public class ChatMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageConsumer.class);

    private final ChatMessageRepository chatMessageRepository;

    public ChatMessageConsumer(ChatMessageRepository chatMessageRepository) {
        this.chatMessageRepository = chatMessageRepository;
    }

    /**
     * Dispatches on the two ChatTopicEvent kinds (see that interface's
     * class comment for why both share this one topic/consumer rather than
     * living on separate topics) — a ChatMessageEvent persists a new
     * message, a MessageDeliveredEvent marks one already-persisted. Because
     * ChatMessagePublisher.publishDelivered keys a delivery event with the
     * SAME conversationKey as the message it acknowledges, Kafka's
     * per-partition ordering guarantees this method always sees the
     * ChatMessageEvent for a given messageId before it can ever see the
     * corresponding MessageDeliveredEvent — there is no code here enforcing
     * that ordering; it falls out entirely from both events landing on the
     * same partition, in production order.
     */
    @KafkaListener(topics = KafkaTopicConfig.CHAT_MESSAGES_TOPIC, groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ChatTopicEvent event) {
        switch (event) {
            case ChatMessageEvent sent -> persistNewMessage(sent);
            case MessageDeliveredEvent delivered -> applyDelivered(delivered);
        }
    }

    private void applyDelivered(MessageDeliveredEvent delivered) {
        int rowsUpdated = chatMessageRepository.markDelivered(delivered.messageId());
        if (rowsUpdated == 0) {
            // Should not happen given the same-partition-key ordering
            // guarantee (see consume()'s Javadoc) — logged as a warning,
            // not silently swallowed, since it would indicate that
            // guarantee was somehow violated (e.g. a topic repartition
            // changing the key->partition mapping between the two events).
            log.warn("markDelivered found no row for message {} despite ordering guarantee - investigate", delivered.messageId());
        }
    }

    private void persistNewMessage(ChatMessageEvent event) {
        // Kafka's default delivery guarantee is "at least once," not
        // "exactly once" — a consumer can see the same record again after,
        // for example, a restart that re-reads a not-yet-committed offset.
        // Without this check, that would insert a second row for the same
        // message. existsByMessageId is the idempotency guard; the unique
        // constraint on ChatMessage.messageId (see the entity) is a
        // database-level backstop for the same guarantee.
        if (chatMessageRepository.existsByMessageId(event.messageId())) {
            log.debug("Message {} already persisted, skipping duplicate delivery", event.messageId());
            return;
        }

        ChatMessage message = new ChatMessage(
                event.messageId(), event.senderId(), event.recipientId(), event.content(), event.sentAt());
        chatMessageRepository.save(message);
        log.debug("Persisted message {}", event.messageId());
    }
}
