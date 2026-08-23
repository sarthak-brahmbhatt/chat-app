package com.chatapp.chatservice.bot;

/**
 * What one model call cost, as reported by the API (CLAUDE.md 3.9 §6).
 *
 * <p>Taken from the response rather than estimated locally: a local tokenizer
 * would have to model the exact serialisation of instructions, chained history
 * and schema, and would drift from the billed number the moment any of that
 * changes. These figures are the ones actually charged.
 *
 * <p>{@code total} is carried rather than computed as input + output because the
 * API reports it, and the two are not always the same arithmetic — reasoning
 * tokens and cache reads land in different buckets depending on the model.
 * Storing what was reported keeps the row honest under a later model swap.
 */
public record TokenUsage(int inputTokens, int outputTokens, int totalTokens) {

    /** For a call that completed without the API reporting usage — rare, but the row still belongs. */
    public static TokenUsage unknown() {
        return new TokenUsage(0, 0, 0);
    }
}
