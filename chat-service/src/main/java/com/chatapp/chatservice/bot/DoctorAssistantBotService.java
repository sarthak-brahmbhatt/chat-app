package com.chatapp.chatservice.bot;

import com.chatapp.chatservice.bot.entity.BotConversationState;
import com.chatapp.chatservice.bot.repository.BotConversationStateRepository;
import com.chatapp.chatservice.bot.entity.BotPromptLog;
import com.chatapp.chatservice.bot.repository.BotPromptLogRepository;
import com.chatapp.chatservice.bot.repository.BotTokenUsageRepository;
import com.chatapp.chatservice.bot.entity.BotTokenUsage;
import com.chatapp.chatservice.service.ChatMessageService;
import com.chatapp.chatservice.support.ConversationKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * One turn of a conversation with the DoctorAssistant bot (CLAUDE.md 3.9 §5.3).
 *
 * <p>Runs the sequence end to end: persist what the user said, gather fresh
 * clinic data, ask the model, act on its decision, persist and return the reply,
 * and record what the call cost. The caller is
 * {@code ChatWebSocketHandler.handleChatMessage}, which has already sent the
 * single tick by the time this is entered.
 *
 * <p>This class returns the reply rather than sending it. Everything to do with
 * WebSocket sessions — finding the sender's socket, serialising an envelope,
 * ticks — stays in the handler, which already owns all of it; a bot service that
 * also wrote to sockets would be a second place that knows the wire format.
 *
 * <p><b>It never throws.</b> A failed turn produces an apology the user can see,
 * because the alternative is a message that gets a single tick and then silence
 * forever, with the reason only in the server log.
 */
@Service
public class DoctorAssistantBotService {

    private static final Logger log = LoggerFactory.getLogger(DoctorAssistantBotService.class);

    private static final String NOT_CONFIGURED_REPLY =
            "Sorry — the appointment assistant isn't available right now. Please try again later.";
    private static final String FAILED_REPLY =
            "Sorry — something went wrong on my end. Could you say that again?";
    private static final String NO_DOCTORS_REPLY =
            "Sorry — there are no doctors available to book with at the moment.";

    private final BotBrain botBrain;
    private final ClinicDataProvider clinicDataProvider;
    private final BotPromptBuilder promptBuilder;
    private final BookingService bookingService;
    private final ChatMessageService chatMessageService;
    private final BotConversationStateRepository conversationStateRepository;
    private final BotTokenUsageRepository tokenUsageRepository;
    private final BotPromptLogRepository promptLogRepository;
    private final boolean logPrompts;
    private final Clock clock;

    public DoctorAssistantBotService(
            BotBrain botBrain,
            ClinicDataProvider clinicDataProvider,
            BotPromptBuilder promptBuilder,
            BookingService bookingService,
            ChatMessageService chatMessageService,
            BotConversationStateRepository conversationStateRepository,
            BotTokenUsageRepository tokenUsageRepository,
            BotPromptLogRepository promptLogRepository,
            @Value("${bot.log-prompts}") boolean logPrompts,
            Clock clock) {
        this.botBrain = botBrain;
        this.clinicDataProvider = clinicDataProvider;
        this.promptBuilder = promptBuilder;
        this.bookingService = bookingService;
        this.chatMessageService = chatMessageService;
        this.conversationStateRepository = conversationStateRepository;
        this.tokenUsageRepository = tokenUsageRepository;
        this.promptLogRepository = promptLogRepository;
        this.logPrompts = logPrompts;
        this.clock = clock;
    }

    /**
     * @param userId      the authenticated sender, from the WebSocket session
     * @param botId       the bot's user id, from BotDirectory
     * @param messageId   the client-generated id of the user's message
     * @param content     what the user typed
     * @param sentAt      the single Instant the handler minted for this message,
     *                    reused here so the persisted row and the live envelope
     *                    carry an identical timestamp (CLAUDE.md build-order 16)
     * @return the bot's reply, ready for the handler to persist and send
     */
    public BotReply handleUserMessage(String userId, String botId, String messageId, String content, Instant sentAt) {
        // Step 1 — the user's message lands in `messages` synchronously, written
        // UNDELIVERED. The caller marks it delivered once the reply is persisted,
        // because in a bot conversation the double tick means "the bot has
        // answered" rather than "it arrived" (CLAUDE.md 3.9). Nothing else will
        // ever flip it — the bot has no browser to send a delivered_ack, and the
        // reconnect sweep only looks at rows where the RECONNECTING user is the
        // recipient, which for these rows is the bot.
        chatMessageService.persistBotConversationMessage(messageId, userId, botId, content, sentAt);

        String conversationKey = ConversationKey.of(userId, botId);

        if (!botBrain.isConfigured()) {
            return reply(NOT_CONFIGURED_REPLY);
        }

        // Steps 3 and 4 — fresh clinic data, and where the conversation left off.
        ClinicSnapshot snapshot = clinicDataProvider.snapshot(Long.parseLong(userId));
        if (snapshot.isEmpty()) {
            // Short-circuited before spending a call. With no active doctors the
            // prompt has nothing to offer and no id the model could legitimately
            // book, so the only correct reply is this one — and a model given an
            // empty dataset is being invited to invent a doctor.
            log.warn("Bot asked about appointments but no active doctors exist");
            return reply(NO_DOCTORS_REPLY);
        }

        String previousResponseId = conversationStateRepository.findByConversationKey(conversationKey)
                .map(BotConversationState::getLastResponseId)
                .orElse(null);

        // Step 5 and 6 — the call, and the structured decision it returns.
        // The prompt is built here rather than inside the retry helper so the
        // exact text can be logged next to the turn it produced; the helper is
        // handed the finished string.
        String systemPrompt = promptBuilder.systemPrompt(snapshot, previousResponseId == null);
        BotTurn turn;
        try {
            turn = respondRecoveringFromExpiredChain(
                    snapshot, systemPrompt, content, previousResponseId, conversationKey);
        } catch (BotBrainException e) {
            // Conversation state is deliberately left untouched. Keeping the last
            // GOOD response id means the next turn still chains onto a coherent
            // history, rather than the failure silently amnesia-ing the
            // conversation on top of whatever went wrong.
            log.warn("Bot turn failed for conversation {}: {}", conversationKey, e.getMessage(), e);
            return reply(FAILED_REPLY);
        }

        // Step 7 — a booking is validated and WRITTEN before any reply goes out.
        // On failure the model's own text is discarded entirely: it was written
        // as a confirmation, and sending it would tell the user they have an
        // appointment that does not exist.
        String replyText = turn.decision().replyToUser();
        if (turn.decision().isBookingRequest()) {
            BookingOutcome outcome = bookingService.book(turn.decision(), Long.parseLong(userId));
            replyText = outcome.replyToUser();
        }

        // Step 9 — chain the conversation forward, and record the cost. Both
        // happen even when a booking was rejected: the turn still occurred and
        // was still billed, and the model must still remember it said whatever
        // it said, or the next turn contradicts it.
        String botMessageId = UUID.randomUUID().toString();
        recordResponseId(conversationKey, turn.responseId());
        int turnNumber = recordTokenUsage(conversationKey, botMessageId, turn);
        recordPrompt(conversationKey, turnNumber, botMessageId, previousResponseId,
                turn, systemPrompt, content, replyText);

        return new BotReply(botMessageId, replyText);
    }

    /**
     * Makes the call, and retries once from scratch if the conversation this turn
     * was chaining onto no longer exists server-side (CLAUDE.md 3.9).
     *
     * <p>Version 1's memory is borrowed: one stored response id, and OpenAI holds
     * the actual prior turns. That retention is not indefinite, so resuming a
     * conversation after a long enough gap presents an id the API no longer
     * knows. This is the ordinary fate of every idle conversation rather than a
     * fault, and it must not reach the user as an error — from their side the bot
     * has simply forgotten the earlier exchange, which is exactly what happened.
     *
     * <p>Retried WITHOUT the stale id, which starts a fresh chain. The new
     * response id is then stored by the caller in the normal way, so the
     * conversation self-heals: the stale value is overwritten and the next turn
     * chains onto something live again.
     *
     * <p>Retried exactly once, and only for this one cause. A second failure is a
     * real failure and propagates. Note also that the retry costs a full second
     * call — worth a line in the log, because a conversation that hits this on
     * every turn would mean the stale id is not being overwritten and the cost is
     * silently doubling.
     *
     * <p>This is recovery, NOT long-term memory. Genuinely remembering a user
     * across days would mean storing the conversation ourselves and replaying it
     * — deferred to Version 2 (CLAUDE.md §5), and explicitly not something
     * previous_response_id chaining provides.
     */
    private BotTurn respondRecoveringFromExpiredChain(
            ClinicSnapshot snapshot, String systemPrompt, String content,
            String previousResponseId, String conversationKey) {
        try {
            return botBrain.respond(systemPrompt, content, previousResponseId);
        } catch (ExpiredConversationException e) {
            log.info("Conversation {} could not chain onto {} (expired server-side); "
                            + "starting a fresh chain. The bot will not recall earlier turns.",
                    conversationKey, previousResponseId);
            // Rebuilt with firstTurn = true. The retry genuinely IS turn one as
            // far as the model can see — it has no history — so the prompt must
            // say so, or it would be told to carry on from a conversation it
            // cannot remember.
            return botBrain.respond(promptBuilder.systemPrompt(snapshot, true), content, null);
        }
    }

    /**
     * A reply that never reached the model — so there is no response id to chain
     * and no tokens to record. It still gets a message id, because it is still
     * about to become a real row in {@code messages} that the user can scroll
     * back to.
     */
    private BotReply reply(String text) {
        return new BotReply(UUID.randomUUID().toString(), text);
    }

    private void recordResponseId(String conversationKey, String responseId) {
        conversationStateRepository.findByConversationKey(conversationKey)
                .ifPresentOrElse(
                        state -> {
                            state.recordResponse(responseId, clock.instant());
                            conversationStateRepository.save(state);
                        },
                        () -> conversationStateRepository.save(
                                new BotConversationState(conversationKey, responseId, clock.instant())));
    }

    /** @return the turn number assigned, so the prompt log can line up with it. */
    private int recordTokenUsage(String conversationKey, String botMessageId, BotTurn turn) {
        // Counted from the rows already written rather than held as a counter, so
        // the number cannot drift from the rows it numbers.
        int turnNumber = Math.toIntExact(tokenUsageRepository.countByConversationKey(conversationKey)) + 1;
        tokenUsageRepository.save(new BotTokenUsage(
                botMessageId,
                conversationKey,
                turnNumber,
                turn.usage().inputTokens(),
                turn.usage().outputTokens(),
                turn.usage().totalTokens(),
                turn.model(),
                clock.instant()));
        return turnNumber;
    }

    /**
     * Stores the exact prompt this turn sent, and what it produced
     * (CLAUDE.md 3.9).
     *
     * <p>Added after a cross-conversation leak that was invisible from the
     * outside: the token counts proved a call happened and what it cost, and
     * said nothing about what it contained. Without the prompt itself there was
     * no way to see that the data handed over was unattributed, which was the
     * whole bug.
     *
     * <p>Failure here must never cost the user their reply — the turn already
     * succeeded and the answer is already on its way, so a logging problem is
     * logged and swallowed rather than turned into an apology.
     */
    private void recordPrompt(
            String conversationKey, int turnNumber, String botMessageId, String previousResponseId,
            BotTurn turn, String systemPrompt, String userMessage, String replyText) {
        if (!logPrompts) {
            return;
        }
        try {
            promptLogRepository.save(new BotPromptLog(
                    conversationKey,
                    turnNumber,
                    botMessageId,
                    previousResponseId,
                    turn.responseId(),
                    systemPrompt,
                    userMessage,
                    replyText,
                    turn.decision().action().name(),
                    clock.instant()));
        } catch (RuntimeException e) {
            log.warn("Could not write prompt log for conversation {} turn {}: {}",
                    conversationKey, turnNumber, e.getMessage());
        }
    }
}
