package com.chatapp.chatservice.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the chat-messages topic as a Spring bean — Spring Boot's
 * auto-configured KafkaAdmin picks up any NewTopic bean and creates that
 * topic on the broker at startup if it doesn't already exist (idempotent:
 * running this against an already-existing topic with the same config is a
 * no-op). Without this, the topic would only spring into existence lazily
 * the first time something tries to produce/consume it — usually fine, but
 * declaring it explicitly means its partition count is a deliberate,
 * visible decision here, not an accident of Kafka's cluster-wide default.
 *
 * 3 partitions: a topic's partition count is its unit of parallelism —
 * more partitions means more consumer instances COULD process it
 * concurrently (see application.yml's group-id comment). 3 is enough to
 * demonstrate that a topic isn't automatically one ordered stream (see
 * ChatMessagePublisher's partitioning explanation) without over-tuning a
 * number that matters more once real message volume/scaling is measured
 * (CLAUDE.md 3.6 — not yet).
 *
 * replicas(1): matches the single-broker Kafka cluster in docker-compose.yml
 * — replicating to more brokers than exist isn't possible. CLAUDE.md 3.4
 * already flags Kafka's own availability as a parked, not-yet-designed
 * concern; this is that same tradeoff surfacing here.
 */
@Configuration
public class KafkaTopicConfig {

    public static final String CHAT_MESSAGES_TOPIC = "chat-messages";

    @Bean
    public NewTopic chatMessagesTopic() {
        return TopicBuilder.name(CHAT_MESSAGES_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
