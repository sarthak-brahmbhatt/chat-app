package com.chatapp.chatservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * A persisted chat message — one row per message in chatappdb's "messages"
 * table (CLAUDE.md 3.5, build-order step 8). Written exclusively by
 * ChatMessageConsumer, asynchronously, after the fact — this entity has
 * nothing to do with the live WebSocket round trip for SENDING a message
 * (ChatWebSocketHandler.handleChatMessage never touches this class or
 * the messages table at all). It IS now also touched from the delivery side — see
 * `delivered` below.
 *
 * messageId is the SAME client-generated correlation id from the WebSocket
 * protocol (CLAUDE.md 3.1) — not a coincidence, and not meant to be a
 * second, unrelated identifier. It's marked unique here specifically for
 * idempotency: Kafka's default delivery guarantee is "at least once," so a
 * consumer can see the same message more than once (e.g. after a restart
 * that reprocesses an uncommitted offset) — the unique constraint is a
 * database-level backstop against ever creating two rows for the one
 * message, on top of ChatMessageConsumer's own existsByMessageId check.
 */
@Entity
@Table(name = "messages", uniqueConstraints = @UniqueConstraint(columnNames = "message_id"))
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, unique = true)
    private String messageId;

    @Column(name = "sender_id", nullable = false)
    private String senderId;

    @Column(name = "recipient_id", nullable = false)
    private String recipientId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    // Build-order step "message history": before this, double-tick was a
    // PURELY LIVE, in-memory concept (ChatWebSocketHandler.handleDeliveredAck
    // just forwarded a TickAck over the socket — see that method — and never
    // touched this table). That was fine as long as tick state only mattered
    // to a client that was live and connected at the moment delivery
    // happened. It stops being fine once history can be fetched later (this
    // column's whole reason for existing): a message delivered five minutes
    // ago must still SHOW as delivered when the conversation is reopened,
    // which means delivery has to be a fact recorded in the database, not
    // just a fact that was once true on a socket that's since closed.
    // Defaults to false (not delivered) - matches "not yet acknowledged,"
    // the correct initial state for a freshly-persisted message.
    @Column(nullable = false)
    private boolean delivered = false;

    protected ChatMessage() {
    }

    public ChatMessage(String messageId, String senderId, String recipientId, String content, Instant sentAt) {
        this.messageId = messageId;
        this.senderId = senderId;
        this.recipientId = recipientId;
        this.content = content;
        this.sentAt = sentAt;
    }

    public Long getId() {
        return id;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getSenderId() {
        return senderId;
    }

    public String getRecipientId() {
        return recipientId;
    }

    public String getContent() {
        return content;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public boolean isDelivered() {
        return delivered;
    }

    /**
     * Marks this message delivered in memory, before it is first saved.
     *
     * <p>Exists for the bot conversation path only (CLAUDE.md 3.9 §5.3). Every
     * other message becomes delivered through the JPQL UPDATE in
     * ChatMessageRepository.markDelivered, driven by a delivered_ack that the
     * recipient's BROWSER sends — and the bot has no browser, so no ack will
     * ever arrive for a message addressed to it. Since the bot demonstrably
     * received the message (it is about to answer it), the row is written
     * delivered from the start rather than inserted false and immediately
     * updated.
     *
     * <p>Only meaningful before the first save. Flipping this on a managed
     * entity would work through dirty checking, but nothing does that — the
     * post-insert path is the repository UPDATE, which is what the Kafka
     * ordering guarantee is built around.
     */
    public void markDelivered() {
        this.delivered = true;
    }
}
