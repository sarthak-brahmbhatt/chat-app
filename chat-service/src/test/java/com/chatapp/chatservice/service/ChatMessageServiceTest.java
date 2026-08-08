package com.chatapp.chatservice.service;

import com.chatapp.chatservice.dto.ConversationHistoryResponse;
import com.chatapp.chatservice.entity.ChatMessage;
import com.chatapp.chatservice.repository.ChatMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A plain Mockito unit test — ChatMessageRepository is mocked, so this
 * verifies ChatMessageService's OWN logic (reversing the repository's
 * most-recent-first order back to oldest-first, building the empty-state
 * message) without needing a real database. The repository's custom @Query
 * itself (does the JPQL actually match either message direction, does the
 * ORDER BY/LIMIT actually pick the right rows) can't be proven by mocking
 * it — that's exactly why this project's established pattern (see
 * ChatMessageConsumerTest's own class comment for the same split) pairs a
 * mocked-repository unit test like this one with a REAL-database manual
 * verification pass (dual-mode: Docker + native) for the query itself.
 */
@ExtendWith(MockitoExtension.class)
class ChatMessageServiceTest {

    @Mock
    private ChatMessageRepository chatMessageRepository;

    private ChatMessage message(String messageId, String senderId, String recipientId, String content, Instant sentAt, boolean delivered) {
        ChatMessage m = new ChatMessage(messageId, senderId, recipientId, content, sentAt);
        if (delivered) {
            // No public mutator on the entity (see its own comment for why
            // markDelivered is a repository-level bulk UPDATE, not an
            // entity setter) - reflection is the only way to build a
            // "delivered" fixture here without adding test-only production
            // API surface just for this.
            try {
                var field = ChatMessage.class.getDeclaredField("delivered");
                field.setAccessible(true);
                field.set(m, true);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
        return m;
    }

    @Test
    void getConversationHistory_withMessages_reversesRepositoryOrderToOldestFirst() {
        ChatMessageService service = new ChatMessageService(chatMessageRepository);
        Instant t1 = Instant.parse("2026-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-01-01T10:01:00Z");
        Instant t3 = Instant.parse("2026-01-01T10:02:00Z");

        // The repository is documented (and named) to return MOST-RECENT-FIRST
        // - t3, t2, t1 - so this stub deliberately returns that order, not
        // the oldest-first order the SERVICE is supposed to produce. If the
        // service forgot to reverse this, the assertion below would catch it.
        when(chatMessageRepository.findConversationMostRecentFirst(eq("42"), eq("99"), any(Pageable.class)))
                .thenReturn(List.of(
                        message("m-3", "99", "42", "third", t3, true),
                        message("m-2", "42", "99", "second", t2, false),
                        message("m-1", "42", "99", "first", t1, true)));

        ConversationHistoryResponse response = service.getConversationHistory("42", "99", null);

        assertThat(response.getMessages()).extracting("messageId").containsExactly("m-1", "m-2", "m-3");
        assertThat(response.getMessage()).isEqualTo("3 message(s) found.");
        // delivered carries through the DTO mapping correctly, per-message -
        // not just reversed order, the actual field values too.
        assertThat(response.getMessages()).extracting("delivered").containsExactly(true, false, true);
    }

    @Test
    void getConversationHistory_withNoMessages_returnsEmptyListWithAppropriateMessage() {
        ChatMessageService service = new ChatMessageService(chatMessageRepository);
        when(chatMessageRepository.findConversationMostRecentFirst(eq("42"), eq("nobody-real"), any(Pageable.class)))
                .thenReturn(List.of());

        ConversationHistoryResponse response = service.getConversationHistory("42", "nobody-real", null);

        assertThat(response.getMessages()).isEmpty();
        // "No messages yet." not a bare empty array with no context - same
        // "appropriate message if none exist" requirement UserListResponse
        // already established for GET /users, applied here too. This exact
        // response is also what a NEW-BUT-REAL conversation partner
        // produces - see ConversationControllerTest for that distinction
        // (or rather, deliberate lack of one) tested at the HTTP layer.
        assertThat(response.getMessage()).isEqualTo("No messages yet.");
        assertThat(response.isHasMore()).isFalse();
    }

    @Test
    void getConversationHistory_requestsMostRecentFiftyOrderedBySentAtDescending() {
        ChatMessageService service = new ChatMessageService(chatMessageRepository);
        when(chatMessageRepository.findConversationMostRecentFirst(eq("42"), eq("99"), any(Pageable.class)))
                .thenReturn(List.of());

        service.getConversationHistory("42", "99", null);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(chatMessageRepository).findConversationMostRecentFirst(eq("42"), eq("99"), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageSize()).isEqualTo(50);
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getSort().getOrderFor("sentAt").isDescending()).isTrue();
    }

    @Test
    void getConversationHistory_withFullPage_reportsHasMoreTrue() {
        ChatMessageService service = new ChatMessageService(chatMessageRepository);
        List<ChatMessage> fiftyMessages = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fiftyMessages.add(message("m-" + i, "42", "99", "content " + i, Instant.parse("2026-01-01T10:00:00Z").plusSeconds(i), true));
        }
        when(chatMessageRepository.findConversationMostRecentFirst(eq("42"), eq("99"), any(Pageable.class)))
                .thenReturn(fiftyMessages);

        ConversationHistoryResponse response = service.getConversationHistory("42", "99", null);

        assertThat(response.isHasMore()).isTrue();
    }

    @Test
    void getConversationHistory_withPartialPage_reportsHasMoreFalse() {
        ChatMessageService service = new ChatMessageService(chatMessageRepository);
        when(chatMessageRepository.findConversationMostRecentFirst(eq("42"), eq("99"), any(Pageable.class)))
                .thenReturn(List.of(message("m-1", "42", "99", "hi", Instant.parse("2026-01-01T10:00:00Z"), true)));

        ConversationHistoryResponse response = service.getConversationHistory("42", "99", null);

        assertThat(response.isHasMore()).isFalse();
    }

    @Test
    void getConversationHistory_withBeforeCursor_delegatesToBeforeQueryNotFirstPageQuery() {
        ChatMessageService service = new ChatMessageService(chatMessageRepository);
        Instant cursor = Instant.parse("2026-01-01T10:00:00Z");
        when(chatMessageRepository.findConversationBeforeMostRecentFirst(eq("42"), eq("99"), eq(cursor), any(Pageable.class)))
                .thenReturn(List.of(message("m-old", "42", "99", "older", cursor.minusSeconds(60), true)));

        ConversationHistoryResponse response = service.getConversationHistory("42", "99", cursor);

        assertThat(response.getMessages()).extracting("messageId").containsExactly("m-old");
        verify(chatMessageRepository, never()).findConversationMostRecentFirst(any(), any(), any());
    }

    @Test
    void markDelivered_delegatesToRepositoryWithGivenMessageId() {
        ChatMessageService service = new ChatMessageService(chatMessageRepository);
        when(chatMessageRepository.markDelivered("m-1")).thenReturn(1);

        service.markDelivered("m-1");

        verify(chatMessageRepository).markDelivered("m-1");
    }

    @Test
    void markDelivered_whenNoRowMatches_doesNotThrow() {
        // As of the Kafka-ordering fix (ChatMessageService.markDelivered's
        // own class comment), this method's only caller is the sweep below,
        // so a zero-rows result here would only realistically come from the
        // accepted concurrent-double-sweep race - either way, this must be
        // handled gracefully (logged, not thrown), not treated as an error.
        ChatMessageService service = new ChatMessageService(chatMessageRepository);
        when(chatMessageRepository.markDelivered("m-already-handled")).thenReturn(0);

        service.markDelivered("m-already-handled");

        verify(chatMessageRepository).markDelivered("m-already-handled");
    }

    @Test
    void sweepUndeliveredForRecipient_withPendingMessages_marksEachDeliveredAndReturnsSenderPairs() {
        ChatMessageService service = new ChatMessageService(chatMessageRepository);
        Instant t1 = Instant.parse("2026-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-01-01T10:01:00Z");
        when(chatMessageRepository.findByRecipientIdAndDeliveredFalse("99")).thenReturn(List.of(
                message("m-1", "42", "99", "first", t1, false),
                message("m-2", "7", "99", "second", t2, false)));
        when(chatMessageRepository.markDelivered(any())).thenReturn(1);

        List<PendingDeliveryNotification> notifications = service.sweepUndeliveredForRecipient("99");

        verify(chatMessageRepository).markDelivered("m-1");
        verify(chatMessageRepository).markDelivered("m-2");
        assertThat(notifications).extracting("messageId", "senderId")
                .containsExactly(tuple("m-1", "42"), tuple("m-2", "7"));
    }

    @Test
    void sweepUndeliveredForRecipient_withNoPendingMessages_returnsEmptyListWithoutTouchingRepository() {
        ChatMessageService service = new ChatMessageService(chatMessageRepository);
        when(chatMessageRepository.findByRecipientIdAndDeliveredFalse("99")).thenReturn(List.of());

        List<PendingDeliveryNotification> notifications = service.sweepUndeliveredForRecipient("99");

        assertThat(notifications).isEmpty();
        verify(chatMessageRepository, never()).markDelivered(any());
    }
}
