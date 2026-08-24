package com.chatapp.chatservice.bot.conversation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row per model call, recording what it cost (CLAUDE.md 3.9 §3.6, §6).
 *
 * <p>This table is the entire point of building Version 1 the naive way. The
 * design deliberately stuffs every doctor, every availability row and every
 * upcoming booking into the prompt on each turn, with no tools and no retrieval,
 * because that approach has a real ceiling — and the argument for Version 2's
 * tool calling is much stronger made with a query against this table than with
 * an assertion that it would probably get expensive.
 *
 * <p>{@code turn_number} is what separates the two things that grow, which is
 * why it is worth storing rather than deriving at read time. Input tokens climb
 * because the stuffed clinic data grows with the number of doctors, AND because
 * {@code previous_response_id} chaining re-sends the whole conversation every
 * turn. Plotted against turn number within one conversation, the second curve is
 * isolated; compared across conversations at equal turn numbers, the first is.
 */
@Entity
@Table(
        name = "bot_token_usage",
        indexes = @Index(name = "idx_token_usage_conversation", columnList = "conversation_key, turn_number"))
public class BotTokenUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The messages.message_id of the BOT REPLY this call produced, so a row here
    // can be lined up against the actual text that cost this much. Nullable and
    // deliberately not a foreign key: a call that fails after billing (a timeout
    // reading the response, a validation failure that discards the reply) still
    // cost real tokens and still belongs in this table, and dropping those rows
    // would quietly under-report spend.
    @Column(name = "message_id")
    private String messageId;

    @Column(name = "conversation_key", nullable = false)
    private String conversationKey;

    @Column(name = "turn_number", nullable = false)
    private int turnNumber;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "total_tokens", nullable = false)
    private int totalTokens;

    // Stored per row, not read from config at analysis time: a model swap is a
    // config change (CLAUDE.md 3.9 §8), and without this column the numbers from
    // before and after the swap would silently pool into one meaningless series.
    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BotTokenUsage() {
    }

    public BotTokenUsage(
            String messageId,
            String conversationKey,
            int turnNumber,
            int inputTokens,
            int outputTokens,
            int totalTokens,
            String model,
            Instant createdAt) {
        this.messageId = messageId;
        this.conversationKey = conversationKey;
        this.turnNumber = turnNumber;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
        this.model = model;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getConversationKey() {
        return conversationKey;
    }

    public int getTurnNumber() {
        return turnNumber;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public String getModel() {
        return model;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
