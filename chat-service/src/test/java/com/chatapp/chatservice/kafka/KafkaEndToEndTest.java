package com.chatapp.chatservice.kafka;

import com.chatapp.chatservice.dto.ChatMessageRequest;
import com.chatapp.chatservice.repository.ChatMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.mockito.InOrder;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An end-to-end test of the REAL Kafka wiring: boots the actual Spring
 * context with a real (temporary, in-process) Kafka broker via
 * @EmbeddedKafka, and proves that a message published through the real
 * ChatMessagePublisher is actually received and processed by the real
 * ChatMessageConsumer over that broker.
 *
 * This is the layer where genuine configuration mistakes tend to hide —
 * topic name typos, a producer/consumer serializer mismatch, a
 * JsonDeserializer trusted-packages misconfiguration, a wrong group-id
 * property placeholder — none of which a mocked-KafkaTemplate unit test
 * (ChatMessagePublisherTest, ChatMessageConsumerTest, or the Kafka-focused
 * tests in ChatWebSocketHandlerTest) can catch, since those never involve
 * real (de)serialization or a real broker at all.
 *
 * @EnableAutoConfiguration(exclude = ...) turns off JPA/DataSource
 * bootstrapping for this test specifically — this test has nothing to do
 * with the database (ChatMessageConsumerTest's mocked-repository tests
 * already cover that), and without excluding it Spring Boot would try to
 * open a real MySQL connection at context startup just to satisfy JPA, for
 * no reason relevant to what this test is actually proving.
 * ChatMessageRepository is provided as a @MockBean instead, purely so
 * ChatMessageConsumer (a real bean here) has something to call.
 */
@SpringBootTest
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class
})
@EmbeddedKafka(partitions = 3, topics = KafkaTopicConfig.CHAT_MESSAGES_TOPIC)
@TestPropertySource(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
class KafkaEndToEndTest {

    @Autowired
    private ChatMessagePublisher chatMessagePublisher;

    @Autowired
    private KafkaListenerEndpointRegistry endpointRegistry;

    @MockBean
    private ChatMessageRepository chatMessageRepository;

    @Test
    void messagePublishedThroughRealBroker_isReceivedAndPersistedByRealConsumer() {
        when(chatMessageRepository.existsByMessageId(anyString())).thenReturn(false);

        // A freshly-started consumer group has no committed offset yet, and
        // Kafka's default policy for that case is auto.offset.reset=latest
        // — "start reading from whatever's produced from now on," NOT "read
        // everything from the beginning." If we published a message before
        // @KafkaListener's container finished joining the consumer group and
        // getting its partitions assigned, the message could land on the
        // topic a moment before the consumer was actually listening, and
        // get silently skipped — not a bug in our code, just a race between
        // "message published" and "consumer group finished forming."
        // waitForAssignment (from spring-kafka-test) blocks until that
        // assignment has genuinely happened, making the test deterministic.
        MessageListenerContainer container = endpointRegistry.getListenerContainers().iterator().next();
        ContainerTestUtils.waitForAssignment(container, 3);

        chatMessagePublisher.publish("42", new ChatMessageRequest("message", "m-e2e-1", "99", "hello via real kafka"));

        // Kafka delivery is asynchronous end-to-end — the consumer runs on
        // its own background poller thread, so the save() call this
        // triggers doesn't happen synchronously within publish() above.
        // await() polls until the assertion passes (or times out and fails
        // with a clear message), the standard way to test eventually-
        // consistent, cross-thread behavior without a flaky fixed sleep.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                verify(chatMessageRepository).save(argThat(message ->
                        message.getMessageId().equals("m-e2e-1")
                                && message.getSenderId().equals("42")
                                && message.getRecipientId().equals("99")
                                && message.getContent().equals("hello via real kafka"))));
    }

    /**
     * The actual proof that CLAUDE.md 4's delivery-ordering fix holds
     * through a REAL broker, not just against a mock: publishes a
     * ChatMessageEvent immediately followed by a MessageDeliveredEvent for
     * the same messageId (same conversationKey, so guaranteed same
     * partition), and asserts via Mockito's InOrder that the consumer
     * processes save() before markDelivered() — exactly the guarantee
     * ChatMessageConsumer.consume()'s Javadoc claims, verified against a
     * real Kafka broker's actual delivery order rather than assumed.
     */
    @Test
    void deliveredEventPublishedAfterMessageEvent_isProcessedInOrder() {
        when(chatMessageRepository.existsByMessageId(anyString())).thenReturn(false);
        when(chatMessageRepository.markDelivered(anyString())).thenReturn(1);

        MessageListenerContainer container = endpointRegistry.getListenerContainers().iterator().next();
        ContainerTestUtils.waitForAssignment(container, 3);

        chatMessagePublisher.publish("42", new ChatMessageRequest("message", "m-order-1", "99", "hello"));
        chatMessagePublisher.publishDelivered("42", "99", "m-order-1");

        InOrder inOrder = inOrder(chatMessageRepository);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            inOrder.verify(chatMessageRepository).save(argThat(message -> message.getMessageId().equals("m-order-1")));
            inOrder.verify(chatMessageRepository).markDelivered("m-order-1");
        });
    }
}
