package com.chatapp.chatservice.kafka;

import com.chatapp.chatservice.entity.ChatMessage;
import com.chatapp.chatservice.repository.ChatMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A plain Mockito unit test for ChatMessageConsumer — ChatMessageRepository
 * is mocked, so this doesn't need a real database or a real Kafka broker
 * (that's KafkaEndToEndTest's job, at a different layer). This isolates the
 * one piece of logic most worth testing directly: idempotent handling of
 * Kafka's at-least-once redelivery.
 */
@ExtendWith(MockitoExtension.class)
class ChatMessageConsumerTest {

    @Mock
    private ChatMessageRepository chatMessageRepository;

    private ChatMessageEvent event(String messageId) {
        return new ChatMessageEvent(messageId, "42", "99", "hello", Instant.now());
    }

    @Test
    void consume_withNewMessage_persistsIt() {
        ChatMessageConsumer consumer = new ChatMessageConsumer(chatMessageRepository);
        when(chatMessageRepository.existsByMessageId("m-1")).thenReturn(false);

        consumer.consume(event("m-1"));

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository).save(captor.capture());
        assertThat(captor.getValue().getMessageId()).isEqualTo("m-1");
        assertThat(captor.getValue().getSenderId()).isEqualTo("42");
        assertThat(captor.getValue().getRecipientId()).isEqualTo("99");
        assertThat(captor.getValue().getContent()).isEqualTo("hello");
    }

    @Test
    void consume_withAlreadyPersistedMessageId_skipsWithoutSavingAgain() {
        // Simulates Kafka's at-least-once redelivery: the same record
        // arrives a second time (e.g. after a consumer restart that
        // re-reads a not-yet-committed offset). Without this check, this
        // would insert a second row for the same logical message.
        ChatMessageConsumer consumer = new ChatMessageConsumer(chatMessageRepository);
        when(chatMessageRepository.existsByMessageId("m-1")).thenReturn(true);

        consumer.consume(event("m-1"));

        verify(chatMessageRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void consume_withMessageDeliveredEvent_marksMessageDelivered() {
        ChatMessageConsumer consumer = new ChatMessageConsumer(chatMessageRepository);
        when(chatMessageRepository.markDelivered("m-1")).thenReturn(1);

        consumer.consume(new MessageDeliveredEvent("m-1"));

        verify(chatMessageRepository).markDelivered("m-1");
        verify(chatMessageRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(chatMessageRepository, never()).existsByMessageId(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void consume_withMessageDeliveredEventMatchingNoRow_logsButDoesNotThrow() {
        // Should not happen given the same-partition-key ordering
        // guarantee - but the code shouldn't blow up if it somehow did.
        ChatMessageConsumer consumer = new ChatMessageConsumer(chatMessageRepository);
        when(chatMessageRepository.markDelivered("m-1")).thenReturn(0);

        consumer.consume(new MessageDeliveredEvent("m-1"));

        verify(chatMessageRepository).markDelivered("m-1");
    }
}
