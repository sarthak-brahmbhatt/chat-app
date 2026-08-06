package com.chatapp.chatservice.service;

import com.chatapp.chatservice.dto.ConversationHistoryResponse;
import com.chatapp.chatservice.dto.ConversationMessageResponse;
import com.chatapp.chatservice.entity.ChatMessage;
import com.chatapp.chatservice.repository.ChatMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The one place messagedb gets read back out for a human to see (as opposed
 * to ChatMessageConsumer, which only ever writes to it). Sits between
 * ConversationController (HTTP-only concerns) and ChatMessageRepository
 * (persistence-only concerns) — same layering as UserService/AuthService in
 * user-service.
 *
 * Also owns markDelivered, called from ChatWebSocketHandler — grouping both
 * "read history" and "record a delivery" here rather than splitting them
 * across two services, since they're the two operations this feature adds
 * to the SAME table for the SAME reason (making delivery status something
 * history can show correctly, not just something a live socket once knew).
 */
@Service
public class ChatMessageService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageService.class);

    // Not a query parameter the client controls — build-order scope for this
    // pass is "populate history when a chat opens," not a scrollable/
    // paginated history view. A fixed limit is simplest given that scope,
    // and 50 is a reasonable amount of context to load a conversation with
    // without needing anything more.
    //
    // "Load more" (cursor/offset-based pagination past this first page) is a
    // documented, deliberate FUTURE item, not solved here - see CLAUDE.md 4
    // for where this is recorded. The reason it's not built now: it needs
    // real product decisions this pass doesn't have an answer for yet (does
    // "load more" prepend older messages above the current scroll position?
    // what's the UX for triggering it - a button, or scroll-to-top?), not
    // just a bigger LIMIT. Building the fetch-more-on-demand mechanics ahead
    // of those decisions would be guessing at requirements that don't exist
    // yet.
    private static final int HISTORY_LIMIT = 50;

    private final ChatMessageRepository chatMessageRepository;

    public ChatMessageService(ChatMessageRepository chatMessageRepository) {
        this.chatMessageRepository = chatMessageRepository;
    }

    /**
     * The last (up to) HISTORY_LIMIT messages between currentUserId and
     * otherUserId, oldest-to-newest — the order a chat window actually
     * renders in (each new message appended at the bottom), not the DESC
     * order the repository query itself uses to correctly pick out the most
     * RECENT N rows (see ChatMessageRepository's own comment for why those
     * two orderings can't both be satisfied by one ORDER BY).
     *
     * "otherUserId doesn't correspond to a real user" and "otherUserId is a
     * real user I've simply never messaged" are, from THIS service's point
     * of view, the exact same case, and deliberately return the identical
     * response - not a design gap. chat-service has no way to tell them
     * apart: it has no users table of its own (messagedb only ever stores
     * message rows, never user records - that's userdb, a different
     * database owned by a different service), and there is no existing
     * cross-service call anywhere in this system for chat-service to ask
     * user-service "does this id exist?" Introducing one just for this
     * would be new service-to-service coupling this architecture has
     * deliberately avoided everywhere else (CLAUDE.md 3.2's service
     * boundaries). A caller passing a nonexistent id simply sees the same
     * "no messages yet" response as a caller starting a real, brand-new
     * conversation - both are true statements about messagedb's own data,
     * which is the only thing this service can actually answer questions
     * about.
     */
    public ConversationHistoryResponse getConversationHistory(String currentUserId, String otherUserId) {
        Pageable mostRecentFirst = PageRequest.of(0, HISTORY_LIMIT, Sort.by("sentAt").descending());

        List<ConversationMessageResponse> descending =
                chatMessageRepository.findConversationMostRecentFirst(currentUserId, otherUserId, mostRecentFirst)
                        .stream()
                        .map(ConversationMessageResponse::from)
                        .toList();

        // .toList() is immutable - Collections.reverse() needs a mutable
        // list to reverse in place, hence the explicit copy.
        List<ConversationMessageResponse> oldestToNewest = new ArrayList<>(descending);
        Collections.reverse(oldestToNewest);

        String message = oldestToNewest.isEmpty()
                ? "No messages yet."
                : oldestToNewest.size() + " message(s) found.";

        return new ConversationHistoryResponse(oldestToNewest, message);
    }

    /**
     * Marks a persisted message delivered. As of the reconnect-sweep
     * feature (CLAUDE.md 3.1/4), this is called from exactly one place:
     * sweepUndeliveredForRecipient below. It is NOT called from the live
     * delivered_ack path anymore - that path now publishes a
     * MessageDeliveredEvent to Kafka on the SAME partition key as the
     * original message instead (ChatWebSocketHandler.handleDeliveredAck,
     * ChatMessagePublisher.publishDelivered), and ChatMessageConsumer calls
     * ChatMessageRepository.markDelivered directly once that event is
     * consumed - Kafka's per-partition ordering guarantee makes that
     * consumer call always run after the row already exists, structurally,
     * not just usually.
     *
     * Because of that, a zero-rows result HERE is a much rarer, different
     * situation than it used to be: this method only ever runs against rows
     * sweepUndeliveredForRecipient just SELECTed as delivered=false moments
     * earlier, in the same request. A concurrent duplicate sweep (two tabs
     * reconnecting for the same recipient near-simultaneously) is the only
     * realistic way this UPDATE could still find nothing to do - see
     * sweepUndeliveredForRecipient's own comment for why that's accepted,
     * not locked against.
     */
    public void markDelivered(String messageId) {
        int rowsUpdated = chatMessageRepository.markDelivered(messageId);
        if (rowsUpdated == 0) {
            log.info("markDelivered found no row for message {} - likely a concurrent duplicate sweep "
                    + "for the same recipient (see this method's Javadoc)", messageId);
        }
    }

    /**
     * Called from ChatWebSocketHandler.authenticate() every time a user's
     * WebSocket connects - including reconnects, not just first-ever
     * connects. Finds every message where this user is the RECIPIENT and
     * delivery was never recorded, marks each delivered, and returns what
     * the caller needs to live-notify each original sender (see
     * PendingDeliveryNotification).
     *
     * This exists to close the one gap Kafka-ordering (see markDelivered's
     * Javadoc) can't reach on its own: a message sent while this recipient
     * had NO live connection at all. deliverIfRecipientConnected never sent
     * incoming_message for it, so the recipient's client never had anything
     * to auto-ack (chat.service.ts's delivered_ack trigger fires only off a
     * live incoming_message) - no delivered_ack was ever produced, Kafka-ordered
     * or otherwise, so nothing would ever mark that row delivered without
     * this sweep. A broad "any delivered=false row for this recipient" query
     * also incidentally catches any row stuck false from BEFORE this whole
     * fix shipped - harmless, since marking an already-delivered row
     * delivered again is a no-op.
     *
     * Deliberately does NOT re-send message CONTENT to this (the
     * recipient's) session - GET /conversations/{otherUserId}/messages,
     * already called by the frontend before this connection even opens
     * (ChatComponent.ngOnInit), remains the only path that delivers content.
     * Re-pushing as incoming_message here would risk a duplicate bubble
     * (chat.service.ts's incomingMessages$ handler appends unconditionally).
     *
     * Concurrency, accepted not solved: two near-simultaneous connects for
     * the same recipient (two tabs, or a stale session's close racing a
     * fresh one) could both see the same rows here and both mark-delivered
     * + notify twice. Harmless - TickAck.doubleTick is idempotent on the
     * frontend (a keyed map, not an append), and marking an already-true row
     * true again is a no-op - so no locking is added, consistent with
     * ConnectionRegistry's own no-locking, single-instance design.
     */
    public List<PendingDeliveryNotification> sweepUndeliveredForRecipient(String recipientId) {
        List<ChatMessage> undelivered = chatMessageRepository.findByRecipientIdAndDeliveredFalse(recipientId);
        if (undelivered.isEmpty()) {
            return List.of();
        }

        List<PendingDeliveryNotification> notifications = new ArrayList<>();
        for (ChatMessage message : undelivered) {
            markDelivered(message.getMessageId());
            notifications.add(new PendingDeliveryNotification(message.getMessageId(), message.getSenderId()));
        }
        return notifications;
    }
}
