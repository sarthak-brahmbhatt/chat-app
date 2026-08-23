package com.chatapp.chatservice.bot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * What makes the bot remember anything across turns (CLAUDE.md 3.9 §3.5, §5.3).
 *
 * <p>Every user message arrives as a separate, stateless WebSocket frame — the
 * handler has no notion of a session beyond "who is authenticated on this
 * socket". Without a row here, each call to the model would start from nothing
 * and the bot would ask "which doctor?" again immediately after being told.
 *
 * <p>The memory itself lives on OpenAI's side: {@code last_response_id} is
 * handed back as {@code previous_response_id} on the next call, and the API
 * reattaches the prior turns. Storing one id is the entire mechanism — this
 * service never accumulates a transcript in memory, so nothing here grows per
 * message or needs expiring, and a restart loses no context.
 *
 * <p>The flip side, and the reason {@code bot_token_usage.turn_number} exists:
 * those reattached turns are re-billed as input tokens on every single call. So
 * input cost climbs on two independent curves at once — the stuffed clinic data
 * (which grows with the number of doctors) and the conversation history (which
 * grows with the length of the chat). Version 1 is built to make both visible.
 */
@Entity
@Table(
        name = "bot_conversation_state",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_bot_conversation_key",
                columnNames = "conversation_key"))
public class BotConversationState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The same canonical pair key Kafka partitions on — see
    // com.chatapp.chatservice.support.ConversationKey for why there is exactly
    // one implementation of it rather than two that agree today.
    @Column(name = "conversation_key", nullable = false, unique = true)
    private String conversationKey;

    // Nullable on purpose: a row can legitimately exist with no response id yet.
    // That is what the very first turn looks like, and also what a turn that
    // failed mid-call leaves behind.
    @Column(name = "last_response_id")
    private String lastResponseId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BotConversationState() {
    }

    public BotConversationState(String conversationKey, String lastResponseId, Instant updatedAt) {
        this.conversationKey = conversationKey;
        this.lastResponseId = lastResponseId;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public String getConversationKey() {
        return conversationKey;
    }

    public String getLastResponseId() {
        return lastResponseId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void recordResponse(String responseId, Instant at) {
        this.lastResponseId = responseId;
        this.updatedAt = at;
    }
}
