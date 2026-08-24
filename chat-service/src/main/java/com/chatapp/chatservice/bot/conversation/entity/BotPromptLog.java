package com.chatapp.chatservice.bot.conversation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The exact prompt sent for one turn, and what came back (CLAUDE.md 3.9).
 *
 * <p>Added after a real cross-conversation leak: two patients chatting in
 * parallel were each told about the other's appointments, and there was no way
 * to see WHY without being able to read the prompt that produced it. The token
 * counts in {@code bot_token_usage} said a call happened and what it cost; they
 * could not say what it contained. This table closes that gap — every turn is
 * reconstructable after the fact, from a SQL client, without attaching a
 * debugger or re-running anything.
 *
 * <p>Separate from {@code bot_token_usage} rather than more columns on it. A
 * prompt here is several kilobytes, and the token table is the one people query
 * repeatedly to plot cost curves; dragging a LONGTEXT into every one of those
 * scans to serve a much rarer question is the wrong trade. They join on
 * {@code (conversation_key, turn_number)}.
 *
 * <p><b>This stores conversation content in the clear</b> — the patient's own
 * words and the clinic's whole dataset, on every row. That is correct for a
 * local learning project whose explicit purpose is making the naive approach's
 * behaviour observable, and it is exactly what would need a retention policy,
 * redaction, or an off switch before this went anywhere real. See
 * {@code bot.log-prompts}.
 */
@Entity
@Table(
        name = "bot_prompt_log",
        indexes = @Index(name = "idx_prompt_log_conversation", columnList = "conversation_key, turn_number"))
public class BotPromptLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_key", nullable = false)
    private String conversationKey;

    @Column(name = "turn_number", nullable = false)
    private int turnNumber;

    /** The id of the bot reply this turn produced, tying the row to a message. */
    @Column(name = "message_id")
    private String messageId;

    /**
     * What was chained FROM, and what the call produced. Together they make the
     * conversation's chain readable as a sequence — which is the first thing to
     * check when a bot appears to have confused two conversations, since a
     * shared or crossed id would show up here immediately.
     */
    @Column(name = "previous_response_id")
    private String previousResponseId;

    @Column(name = "response_id")
    private String responseId;

    /**
     * The complete {@code instructions} payload: behavioural rules AND the whole
     * stuffed clinic dataset, byte for byte as the model received it.
     *
     * <p>The dataset half is the point. A leak like the one that prompted this
     * table is not visible in the rules — it is visible in what data was handed
     * over and how it was labelled.
     */
    @Lob
    @Column(name = "system_prompt", nullable = false, columnDefinition = "LONGTEXT")
    private String systemPrompt;

    @Lob
    @Column(name = "user_message", nullable = false, columnDefinition = "TEXT")
    private String userMessage;

    @Lob
    @Column(name = "reply_to_user", columnDefinition = "TEXT")
    private String replyToUser;

    /** BOOK or NONE, or null when the turn never reached the model. */
    @Column(name = "action", length = 20)
    private String action;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BotPromptLog() {
    }

    public BotPromptLog(
            String conversationKey,
            int turnNumber,
            String messageId,
            String previousResponseId,
            String responseId,
            String systemPrompt,
            String userMessage,
            String replyToUser,
            String action,
            Instant createdAt) {
        this.conversationKey = conversationKey;
        this.turnNumber = turnNumber;
        this.messageId = messageId;
        this.previousResponseId = previousResponseId;
        this.responseId = responseId;
        this.systemPrompt = systemPrompt;
        this.userMessage = userMessage;
        this.replyToUser = replyToUser;
        this.action = action;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getConversationKey() {
        return conversationKey;
    }

    public int getTurnNumber() {
        return turnNumber;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getPreviousResponseId() {
        return previousResponseId;
    }

    public String getResponseId() {
        return responseId;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public String getReplyToUser() {
        return replyToUser;
    }

    public String getAction() {
        return action;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
