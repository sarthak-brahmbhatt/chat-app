package com.chatapp.chatservice.bot;

/**
 * The model call did not produce a usable turn — network failure, API error,
 * timeout, a refusal, or a response with no parseable content.
 *
 * <p>One exception type for all of those on purpose: the caller's response is
 * identical either way (apologise to the user, log, leave the conversation state
 * untouched so the next turn can still chain from the last GOOD response), so
 * distinguishing them in the type system would create branches nobody takes. The
 * cause is preserved for the log, which is where the distinction actually gets
 * used.
 */
public class BotBrainException extends RuntimeException {

    public BotBrainException(String message) {
        super(message);
    }

    public BotBrainException(String message, Throwable cause) {
        super(message, cause);
    }
}
