package com.chatapp.chatservice.repository;

import com.chatapp.chatservice.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The only place in chat-service that talks to messagedb directly — same
 * layering discipline as user-service's UserRepository.
 *
 * existsByMessageId is what makes ChatMessageConsumer's persistence
 * idempotent: a derived query Spring Data JPA generates from the method
 * name, same mechanism as UserRepository.existsByUsername.
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    boolean existsByMessageId(String messageId);

    /**
     * Every message between two users, regardless of who sent which one —
     * a conversation isn't "messages I sent to them," it's "messages
     * exchanged between us both ways." A derived method name for an OR
     * across two column-pairs gets unreadable fast (something like
     * findBySenderIdAndRecipientIdOrRecipientIdAndSenderId, with FOUR
     * positional parameters in an order that's easy to get backwards) —
     * this is exactly the kind of query Spring Data's naming convention
     * handles awkwardly, which is what an explicit @Query is for.
     *
     * Ordered DESCENDING (most recent first), not the "oldest-to-newest"
     * order the API contract actually promises — deliberate, and Pageable
     * is what makes it necessary: LIMIT-ing a result set only makes sense
     * paired with an ORDER BY that puts the rows you want to KEEP first.
     * Sorting ASCENDING and taking the first page would return the
     * OLDEST N messages in a long conversation, not the most recent N,
     * which is the wrong slice for "what should load when you open a
     * chat." ChatMessageService is what reverses this back to
     * oldest-to-newest afterward, once the right N rows have already been
     * selected — reversing a small in-memory list is cheap; there's no
     * equivalent way to ask the database for "the last N, but hand them
     * back already forwards."
     */
    @Query("""
            SELECT m FROM ChatMessage m
            WHERE (m.senderId = :userA AND m.recipientId = :userB)
               OR (m.senderId = :userB AND m.recipientId = :userA)
            ORDER BY m.sentAt DESC
            """)
    List<ChatMessage> findConversationMostRecentFirst(
            @Param("userA") String userA, @Param("userB") String userB, Pageable pageable);

    /**
     * A direct bulk UPDATE rather than find-then-save: this only ever needs
     * to flip one boolean on one row by its unique messageId, so loading the
     * full entity into a persistence context just to call a setter and let
     * Hibernate's dirty-checking issue the same UPDATE is unnecessary
     * ceremony for what's a single, self-contained write.
     *
     * @Modifying is required for any @Query that isn't a SELECT - without
     * it, Spring Data JPA assumes a Query returns entities and throws
     * rather than execute an update. @Transactional is required alongside
     * it: a modifying query has to run inside a transaction, and unlike a
     * plain save() (which JpaRepository's own default methods already wrap
     * in one), a custom @Modifying method needs it declared explicitly.
     *
     * Returns the number of rows updated (0 or 1) rather than void,
     * specifically so the caller (ChatWebSocketHandler, via
     * ChatMessageService) can detect the race documented there: a
     * delivered_ack can arrive before Kafka's consumer has persisted the
     * message it refers to, in which case this returns 0 - there's no row
     * yet to mark. See ChatMessageService.markDelivered for how that's
     * handled.
     */
    @Modifying
    @Transactional
    @Query("UPDATE ChatMessage m SET m.delivered = true WHERE m.messageId = :messageId")
    int markDelivered(@Param("messageId") String messageId);

    /**
     * Every message where the given user is the RECIPIENT and delivery
     * hasn't been recorded yet — the reconnect-sweep's input set
     * (ChatWebSocketHandler.authenticate, via
     * ChatMessageService.sweepUndeliveredForRecipient). A plain derived
     * query, not a bulk UPDATE: the sweep needs each row's messageId AND
     * senderId afterward (to know who to live-notify), which a bulk
     * UPDATE's rows-affected count can't provide - a SELECT-first shape is
     * required here, unlike markDelivered above which only ever needs to
     * flip one already-known id.
     */
    List<ChatMessage> findByRecipientIdAndDeliveredFalse(String recipientId);
}
