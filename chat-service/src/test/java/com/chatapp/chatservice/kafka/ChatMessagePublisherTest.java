package com.chatapp.chatservice.kafka;

import com.chatapp.chatservice.dto.ChatMessageRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A focused unit test for ChatMessagePublisher, isolated from
 * ChatWebSocketHandler entirely — KafkaTemplate is the only mock, at the
 * Kafka-library boundary.
 */
@ExtendWith(MockitoExtension.class)
class ChatMessagePublisherTest {

    @Mock
    private KafkaTemplate<String, ChatMessageEvent> kafkaTemplate;

    private ChatMessageRequest request(String messageId, String recipientId, String content) {
        return new ChatMessageRequest("message", messageId, recipientId, content);
    }

    @Test
    void publish_sendsToChatMessagesTopicWithCorrectEventContent() {
        lenient().when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(new CompletableFuture<>());
        ChatMessagePublisher publisher = new ChatMessagePublisher(kafkaTemplate);

        publisher.publish("42", request("m-1", "99", "hello"));

        ArgumentCaptor<ChatMessageEvent> eventCaptor = ArgumentCaptor.forClass(ChatMessageEvent.class);
        verify(kafkaTemplate).send(eq(KafkaTopicConfig.CHAT_MESSAGES_TOPIC), eq("42:99"), eventCaptor.capture());
        ChatMessageEvent event = eventCaptor.getValue();
        assertThat(event.messageId()).isEqualTo("m-1");
        assertThat(event.senderId()).isEqualTo("42");
        assertThat(event.recipientId()).isEqualTo("99");
        assertThat(event.content()).isEqualTo("hello");
        assertThat(event.sentAt()).isNotNull();
    }

    @Test
    void conversationKey_isTheSameRegardlessOfWhoIsSenderOrRecipient() {
        // The core ordering claim: Alice -> Bob and Bob -> Alice must
        // produce the IDENTICAL key, or Kafka's per-partition ordering
        // guarantee wouldn't cover both directions of one conversation —
        // see ChatMessagePublisher's class comment for the full reasoning.
        String aliceToBob = ChatMessagePublisher.conversationKey("alice-id", "bob-id");
        String bobToAlice = ChatMessagePublisher.conversationKey("bob-id", "alice-id");

        assertThat(aliceToBob).isEqualTo(bobToAlice);
    }

    @Test
    void conversationKey_differsForADifferentConversation() {
        // Sanity check on the other direction of the claim: two DIFFERENT
        // pairs of participants must not collide onto the same key (which
        // would force unrelated conversations onto the same partition for
        // no reason, hurting parallelism without any ordering benefit).
        String conversationOne = ChatMessagePublisher.conversationKey("alice-id", "bob-id");
        String conversationTwo = ChatMessagePublisher.conversationKey("alice-id", "carol-id");

        assertThat(conversationOne).isNotEqualTo(conversationTwo);
    }

    @Test
    @Timeout(2)
    void publish_neverBlocksEvenWhenKafkaNeverResponds() {
        // A future that never completes — if publish() ever called
        // .get()/.join() on the result of kafkaTemplate.send(...), this
        // test would hang until @Timeout(2) fails it, rather than passing
        // silently. See ChatMessagePublisher's class comment for why this
        // matters (CLAUDE.md 3.4's single-tick guarantee depends on it).
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(new CompletableFuture<>());
        ChatMessagePublisher publisher = new ChatMessagePublisher(kafkaTemplate);

        publisher.publish("42", request("m-2", "99", "hello"));
        // Reaching this line at all is the assertion — publish() returned.
    }

    @Test
    void publish_swallowsASynchronousKafkaException() {
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("simulated Kafka producer failure"));
        ChatMessagePublisher publisher = new ChatMessagePublisher(kafkaTemplate);

        // No assertThrows here on purpose: the point is that NOTHING is
        // thrown back to the caller. If publish() let this propagate, this
        // test method itself would fail with an unexpected exception.
        publisher.publish("42", request("m-3", "99", "hello"));
    }
}
