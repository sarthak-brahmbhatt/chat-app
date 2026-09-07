package com.chatapp.chatservice.bot.conversation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row per API call to the model — the raw traffic behind a turn.
 *
 * <p>Sits below {@code bot_prompt_log}, which has one row per TURN. A Version 2
 * turn that took three rounds is one prompt-log row and three rows here, and
 * reading them in order is reading the whole conversation with OpenAI: what was
 * sent, what came back, what it cost, and which response each round chained
 * from.
 *
 * <p>{@code promptLogId} is the parent's primary key rather than a repeat of
 * (conversation_key, turn_number) — those two are kept as well, because they are
 * what a human filters on, but the id is what makes the parent unambiguous.
 * Nullable only because the parent is not written when {@code bot.log-prompts}
 * is off; the rounds are still worth having on their own.
 */
@Entity
@Table(name = "bot_round_log")
public class BotRoundLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code bot_prompt_log.id} for the turn these rounds belong to. */
    @Column(name = "prompt_log_id")
    private Long promptLogId;

    @Column(name = "conversation_key", nullable = false)
    private String conversationKey;

    @Column(name = "turn_number", nullable = false)
    private int turnNumber;

    /** 1-based, in the order the calls were actually made. */
    @Column(name = "round_number", nullable = false)
    private int roundNumber;

    /**
     * The exact request body. For round 2 onward this is where you can SEE that
     * the instructions and the tool schemas were re-sent — the single most
     * surprising thing about the loop, and not visible anywhere else.
     */
    @Lob
    @Column(name = "request_json", nullable = false, columnDefinition = "LONGTEXT")
    private String requestJson;

    /** The whole response, including any function calls and the usage block. */
    @Lob
    @Column(name = "response_json", columnDefinition = "LONGTEXT")
    private String responseJson;

    @Column(name = "previous_response_id")
    private String previousResponseId;

    @Column(name = "response_id")
    private String responseId;

    /**
     * This round's tokens. {@code bot_token_usage} holds the turn's TOTAL, so
     * these are what that total is made of — and what shows that each round
     * re-bills everything before it.
     */
    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BotRoundLog() {
    }

    public BotRoundLog(
            Long promptLogId,
            String conversationKey,
            int turnNumber,
            int roundNumber,
            String requestJson,
            String responseJson,
            String previousResponseId,
            String responseId,
            int inputTokens,
            int outputTokens,
            Instant createdAt) {
        this.promptLogId = promptLogId;
        this.conversationKey = conversationKey;
        this.turnNumber = turnNumber;
        this.roundNumber = roundNumber;
        this.requestJson = requestJson;
        this.responseJson = responseJson;
        this.previousResponseId = previousResponseId;
        this.responseId = responseId;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getPromptLogId() {
        return promptLogId;
    }

    public String getConversationKey() {
        return conversationKey;
    }

    public int getTurnNumber() {
        return turnNumber;
    }

    public int getRoundNumber() {
        return roundNumber;
    }

    public String getRequestJson() {
        return requestJson;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public String getPreviousResponseId() {
        return previousResponseId;
    }

    public String getResponseId() {
        return responseId;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
