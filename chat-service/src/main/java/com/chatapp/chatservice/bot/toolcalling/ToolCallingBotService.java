package com.chatapp.chatservice.bot.toolcalling;

import com.chatapp.chatservice.bot.clinic.AvailabilityService;
import com.chatapp.chatservice.bot.clinic.BookingService;
import com.chatapp.chatservice.bot.clinic.repository.AppointmentRepository;
import com.chatapp.chatservice.bot.clinic.repository.DoctorAvailabilityRepository;
import com.chatapp.chatservice.bot.clinic.repository.DoctorRepository;
import com.chatapp.chatservice.bot.conversation.entity.BotConversationState;
import com.chatapp.chatservice.bot.conversation.RoundRecord;
import com.chatapp.chatservice.bot.conversation.entity.BotPromptLog;
import com.chatapp.chatservice.bot.conversation.entity.BotRoundLog;
import com.chatapp.chatservice.bot.conversation.entity.BotTokenUsage;
import com.chatapp.chatservice.bot.conversation.repository.BotConversationStateRepository;
import com.chatapp.chatservice.bot.conversation.repository.BotPromptLogRepository;
import com.chatapp.chatservice.bot.conversation.repository.BotRoundLogRepository;
import com.chatapp.chatservice.bot.conversation.repository.BotTokenUsageRepository;
import com.chatapp.chatservice.bot.promptstuffing.BotBrainException;
import com.chatapp.chatservice.bot.promptstuffing.ExpiredConversationException;
import com.chatapp.chatservice.bot.routing.BotReply;
import com.chatapp.chatservice.service.ChatMessageService;
import com.chatapp.chatservice.support.ConversationKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * One turn of a conversation with the tool-calling bot (CLAUDE.md 3.10).
 *
 * <p>Structurally the same sequence as {@code DoctorAssistantBotService} —
 * persist, look up where the conversation left off, call the model, record what
 * it cost — and deliberately so, because keeping the surrounding machinery
 * identical is what makes the two versions comparable. The difference is
 * confined to the middle: no clinic snapshot is gathered, no decision is
 * interpreted, and the model reaches the database itself through tools that
 * validate.
 *
 * <p>Notice what is <b>absent</b> compared to Version 1: no
 * {@code ClinicDataProvider}, no {@code ClinicSnapshot}, and no branch that
 * inspects a returned action to decide whether to book. Booking is a tool call
 * the model makes, and {@code BookingService} refuses it if it is wrong — the
 * same refusals, reached a different way.
 *
 * <p>Like Version 1, it never throws. A failed turn becomes an apology the
 * patient can see.
 */
@Service
public class ToolCallingBotService {

    private static final Logger log = LoggerFactory.getLogger(ToolCallingBotService.class);

    private static final String NOT_CONFIGURED_REPLY =
            "Sorry — the appointment assistant isn't available right now. Please try again later.";
    private static final String FAILED_REPLY =
            "Sorry — something went wrong on my end. Could you say that again?";

    private final ToolCallingBrain brain;
    private final ToolBotPromptBuilder promptBuilder;
    private final ChatMessageService chatMessageService;
    private final BotConversationStateRepository conversationStateRepository;
    private final BotTokenUsageRepository tokenUsageRepository;
    private final BotPromptLogRepository promptLogRepository;
    private final BotRoundLogRepository roundLogRepository;

    private final DoctorRepository doctorRepository;
    private final DoctorAvailabilityRepository availabilityRepository;
    private final AppointmentRepository appointmentRepository;
    private final AvailabilityService availabilityService;
    private final BookingService bookingService;
    private final ObjectMapper objectMapper;

    private final boolean logPrompts;
    private final boolean logRounds;
    private final int horizonDays;
    private final Clock clock;

    public ToolCallingBotService(
            ToolCallingBrain brain,
            ToolBotPromptBuilder promptBuilder,
            ChatMessageService chatMessageService,
            BotConversationStateRepository conversationStateRepository,
            BotTokenUsageRepository tokenUsageRepository,
            BotPromptLogRepository promptLogRepository,
            BotRoundLogRepository roundLogRepository,
            DoctorRepository doctorRepository,
            DoctorAvailabilityRepository availabilityRepository,
            AppointmentRepository appointmentRepository,
            AvailabilityService availabilityService,
            BookingService bookingService,
            ObjectMapper objectMapper,
            @Value("${bot.log-prompts}") boolean logPrompts,
            @Value("${bot.log-rounds}") boolean logRounds,
            @Value("${bot.booking-horizon-days}") int horizonDays,
            Clock clock) {
        this.brain = brain;
        this.promptBuilder = promptBuilder;
        this.chatMessageService = chatMessageService;
        this.conversationStateRepository = conversationStateRepository;
        this.tokenUsageRepository = tokenUsageRepository;
        this.promptLogRepository = promptLogRepository;
        this.roundLogRepository = roundLogRepository;
        this.doctorRepository = doctorRepository;
        this.availabilityRepository = availabilityRepository;
        this.appointmentRepository = appointmentRepository;
        this.availabilityService = availabilityService;
        this.bookingService = bookingService;
        this.objectMapper = objectMapper;
        this.logPrompts = logPrompts;
        this.logRounds = logRounds;
        this.horizonDays = horizonDays;
        this.clock = clock;
    }

    /**
     * @param replyMessageId the id the bot's reply will have, minted by the
     *                       CALLER rather than here. Streaming needs it before
     *                       the turn finishes — every stream frame carries it so
     *                       the client knows which bubble to grow — and the id
     *                       of a message cannot be decided after the message has
     *                       started arriving.
     * @param listener       receives the reply as it is written; pass
     *                       {@link BotStreamListener#NOOP} for none
     */
    public BotReply handleUserMessage(String userId, String botId, String messageId, String content,
                                      Instant sentAt, String replyMessageId, BotStreamListener listener) {
        chatMessageService.persistBotConversationMessage(messageId, userId, botId, content, sentAt);

        String conversationKey = ConversationKey.of(userId, botId);

        if (!brain.isConfigured()) {
            return reply(NOT_CONFIGURED_REPLY);
        }

        String previousResponseId = conversationStateRepository.findByConversationKey(conversationKey)
                .map(BotConversationState::getLastResponseId)
                .orElse(null);

        // Built per turn and per PATIENT. The executor closes over the
        // authenticated user id, which is what makes "show me my appointments"
        // answerable and "show me theirs" inexpressible.
        ClinicToolExecutor executor = new ClinicToolExecutor(
                doctorRepository, availabilityRepository, appointmentRepository,
                availabilityService, bookingService, objectMapper, clock,
                horizonDays, Long.parseLong(userId));

        String systemPrompt = promptBuilder.systemPrompt(previousResponseId == null);

        ToolTurn turn;
        try {
            turn = respondRecoveringFromExpiredChain(systemPrompt, content, previousResponseId,
                    executor, conversationKey, listener);
        } catch (BotBrainException e) {
            log.warn("Tool-calling turn failed for conversation {}: {}", conversationKey, e.getMessage(), e);
            return reply(FAILED_REPLY);
        }

        String botMessageId = replyMessageId;
        recordResponseId(conversationKey, turn.responseId());
        int turnNumber = recordTokenUsage(conversationKey, botMessageId, turn);
        Long promptLogId = recordPrompt(
                conversationKey, turnNumber, botMessageId, previousResponseId, turn, systemPrompt, content);
        recordRounds(conversationKey, turnNumber, promptLogId, turn.roundLog());

        return new BotReply(botMessageId, turn.replyToUser());
    }

    /**
     * Same one-shot recovery as Version 1: if the chain has aged out of OpenAI's
     * retention, retry once from scratch rather than surfacing an error. See
     * {@code DoctorAssistantBotService} for the full reasoning — it applies
     * identically, and the prompt is rebuilt with {@code firstTurn = true}
     * because the retry genuinely is turn one as far as the model can see.
     */
    private ToolTurn respondRecoveringFromExpiredChain(
            String systemPrompt, String content, String previousResponseId,
            ClinicToolExecutor executor, String conversationKey, BotStreamListener listener) {
        try {
            return brain.respond(systemPrompt, content, previousResponseId, executor, listener);
        } catch (ExpiredConversationException e) {
            log.info("Conversation {} could not chain onto {} (expired server-side); starting a fresh chain.",
                    conversationKey, previousResponseId);
            // The retry streams too. Anything already sent is discarded by the
            // client when the final incoming_message replaces the bubble, so a
            // half-streamed abandoned attempt cannot leave stray text behind.
            return brain.respond(promptBuilder.systemPrompt(true), content, null, executor, listener);
        }
    }

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

    /**
     * Writes to the SAME table Version 1 uses, which is what makes the two
     * directly comparable — one query, grouped by conversation key, shows both
     * cost curves side by side. The numbers here are summed across every round
     * of the loop, so nothing is hidden by the multi-call shape.
     */
    private int recordTokenUsage(String conversationKey, String botMessageId, ToolTurn turn) {
        int turnNumber = Math.toIntExact(tokenUsageRepository.countByConversationKey(conversationKey)) + 1;
        tokenUsageRepository.save(new BotTokenUsage(
                botMessageId, conversationKey, turnNumber,
                turn.usage().inputTokens(), turn.usage().outputTokens(), turn.usage().totalTokens(),
                turn.model(), clock.instant()));
        return turnNumber;
    }

    private Long recordPrompt(String conversationKey, int turnNumber, String botMessageId,
                              String previousResponseId, ToolTurn turn, String systemPrompt, String userMessage) {
        if (!logPrompts) {
            return null;
        }
        try {
            // The tool trace is this bot's equivalent of Version 1's giant
            // prompt: the record of what it actually looked at. Without it the
            // log would show a small prompt and a reply with nothing in between.
            String trace = objectMapper.writeValueAsString(
                    ClinicToolExecutor.traceOf(turn.invocations()));

            // The schemas are identical on every request, and stored anyway:
            // they are ~2,600 characters that go up on EVERY round, so a
            // five-round turn paid for them five times. Leaving them out made
            // the logged prompt size disagree with the billed input tokens for
            // no good reason.
            String schema = objectMapper.writeValueAsString(ClinicTool.allAsFunctionTools());

            BotPromptLog row = new BotPromptLog(
                    conversationKey, turnNumber, botMessageId,
                    previousResponseId, turn.responseId(),
                    systemPrompt, userMessage, turn.replyToUser(),
                    "TOOLS:" + turn.rounds(),
                    trace,
                    schema,
                    clock.instant());
            return promptLogRepository.save(row).getId();
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Could not write prompt log for conversation {} turn {}: {}",
                    conversationKey, turnNumber, e.getMessage());
            return null;
        }
    }

    /**
     * The raw traffic behind the turn — one row per API call.
     *
     * <p>{@code bot_prompt_log} shows the prompt built ONCE at the start of the
     * turn. That is not what was sent on rounds 2 and 3, and the difference (the
     * instructions and tool schemas going up again, the growing chain) is the
     * whole reason the loop costs what it does. These rows are where that is
     * visible.
     *
     * <p>Best-effort: a failure here is logged and swallowed. The turn already
     * happened and the patient already has their answer.
     */
    private void recordRounds(String conversationKey, int turnNumber,
                              Long promptLogId, java.util.List<RoundRecord> rounds) {
        if (!logRounds || rounds.isEmpty()) {
            return;
        }
        try {
            Instant now = clock.instant();
            roundLogRepository.saveAll(rounds.stream()
                    .map(r -> new BotRoundLog(
                            promptLogId, conversationKey, turnNumber, r.roundNumber(),
                            r.requestJson(), r.responseJson(),
                            r.previousResponseId(), r.responseId(),
                            r.inputTokens(), r.outputTokens(), now))
                    .toList());
        } catch (RuntimeException e) {
            log.warn("Could not write round log for conversation {} turn {}: {}",
                    conversationKey, turnNumber, e.getMessage());
        }
    }
}
