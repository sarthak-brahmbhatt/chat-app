package com.chatapp.chatservice.service;

import com.chatapp.chatservice.dto.ConversationHistoryResponse;
import com.chatapp.chatservice.dto.ConversationMessageResponse;
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
     * Marks a persisted message delivered, called from
     * ChatWebSocketHandler.handleDeliveredAck the same moment the LIVE
     * double-tick is sent to the sender - so the database and whatever the
     * sender's screen shows agree, from the instant delivery happens.
     *
     * A REAL, accepted race, documented rather than silently possible to
     * hit and only discover later: Kafka's publish (on send) and consume
     * (the actual DB write, done asynchronously by ChatMessageConsumer) sit
     * between "message sent" and "row exists in messagedb" - CLAUDE.md 3.4's
     * whole reason for that async design. A delivered_ack, by contrast, only
     * has to travel: sender -> chat-service -> recipient's live socket ->
     * recipient's client -> back to chat-service - no Kafka hop at all, and
     * typically faster than Kafka's own produce-then-consume round trip.
     * So it's entirely possible - confirmed empirically during this
     * feature's own manual verification, not just a theoretical corner case
     * - for a delivered_ack to arrive at chat-service BEFORE the row it
     * refers to has been written yet, in which case this UPDATE matches
     * zero rows - there's nothing yet to mark. On a local/low-latency setup
     * this was observed to be the TYPICAL outcome for a promptly-acking
     * recipient, not a rare one: the delivered_ack's path (sender ->
     * chat-service -> recipient's live socket -> recipient's client -> back
     * to chat-service, no Kafka hop) routinely wins the race against
     * Kafka's own produce-then-consume round trip. When that happens, the
     * delivery is NOT retried or queued: the LIVE double-tick the sender's
     * screen shows in that moment is still entirely correct (that path
     * never touches the database at all - see
     * ChatWebSocketHandler.handleDeliveredAck), but a LATER read of
     * persisted history (GET /conversations/{otherUserId}/messages) can
     * show that same message as still single-tick, for a message that
     * really was delivered live moments earlier - expect this, not just as
     * an edge case, whenever history is fetched shortly after a fast
     * exchange. Consistent with this project's existing accepted-tradeoff
     * posture on Kafka timing (CLAUDE.md 3.4's own "if the publish to Kafka
     * fails outright... that message is not retried or persisted
     * anywhere") rather than a new, inconsistent standard just for this one
     * field - building a retry or a pending-acks table to close this gap
     * would be real, unrequested scope beyond what this pass asked for.
     */
    public void markDelivered(String messageId) {
        int rowsUpdated = chatMessageRepository.markDelivered(messageId);
        if (rowsUpdated == 0) {
            log.info("markDelivered found no row yet for message {} - Kafka hasn't persisted it yet, "
                    + "routinely the case for a fast delivered_ack, not a rare one "
                    + "(see this method's Javadoc for why that's an accepted, not-retried race)", messageId);
        }
    }
}
