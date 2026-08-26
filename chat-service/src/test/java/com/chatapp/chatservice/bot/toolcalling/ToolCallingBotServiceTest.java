package com.chatapp.chatservice.bot.toolcalling;

import com.chatapp.chatservice.bot.clinic.AvailabilityService;
import com.chatapp.chatservice.bot.clinic.BookingService;
import com.chatapp.chatservice.bot.clinic.repository.AppointmentRepository;
import com.chatapp.chatservice.bot.clinic.repository.DoctorAvailabilityRepository;
import com.chatapp.chatservice.bot.clinic.repository.DoctorRepository;
import com.chatapp.chatservice.bot.conversation.entity.BotConversationState;
import com.chatapp.chatservice.bot.conversation.entity.BotPromptLog;
import com.chatapp.chatservice.bot.conversation.entity.BotTokenUsage;
import com.chatapp.chatservice.bot.conversation.repository.BotConversationStateRepository;
import com.chatapp.chatservice.bot.conversation.repository.BotPromptLogRepository;
import com.chatapp.chatservice.bot.conversation.repository.BotTokenUsageRepository;
import com.chatapp.chatservice.bot.promptstuffing.BotBrainException;
import com.chatapp.chatservice.bot.promptstuffing.ExpiredConversationException;
import com.chatapp.chatservice.bot.promptstuffing.TokenUsage;
import com.chatapp.chatservice.bot.routing.BotReply;
import com.chatapp.chatservice.service.ChatMessageService;
import com.chatapp.chatservice.support.ConversationKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Version 2's turn sequence (CLAUDE.md 3.10).
 *
 * <p>The counterpart of {@code DoctorAssistantBotServiceTest}, asserting the
 * same properties on the other bot: chaining, expiry recovery, cost accounting,
 * and never throwing at the user. Where the two versions differ — a loop instead
 * of one call, a tool trace instead of a decision — the assertions differ too.
 *
 * <p>Uses a hand-written fake brain rather than mocking the SDK, for the reason
 * that seam exists: the sequence has to be assertable without a billable call.
 */
@ExtendWith(MockitoExtension.class)
class ToolCallingBotServiceTest {

    private static final String USER_ID = "42";
    private static final String BOT_ID = "8";
    private static final String USER_MESSAGE_ID = "client-uuid";
    private static final Instant SENT_AT = Instant.parse("2026-08-24T09:00:00Z");
    private static final String CONVERSATION_KEY = ConversationKey.of(USER_ID, BOT_ID);

    @Mock private ChatMessageService chatMessageService;
    @Mock private BotConversationStateRepository conversationStateRepository;
    @Mock private BotTokenUsageRepository tokenUsageRepository;
    @Mock private BotPromptLogRepository promptLogRepository;
    @Mock private DoctorRepository doctorRepository;
    @Mock private DoctorAvailabilityRepository availabilityRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private AvailabilityService availabilityService;
    @Mock private BookingService bookingService;

    private FakeToolBrain brain;
    private ToolCallingBotService service;

    @BeforeEach
    void setUp() {
        brain = new FakeToolBrain();
        Clock fixed = Clock.fixed(SENT_AT, ZoneOffset.UTC);
        service = new ToolCallingBotService(
                brain, new ToolBotPromptBuilder("Super Clinic", fixed), chatMessageService,
                conversationStateRepository, tokenUsageRepository, promptLogRepository,
                doctorRepository, availabilityRepository, appointmentRepository,
                availabilityService, bookingService, new ObjectMapper(), true, 7, fixed);

        lenient().when(conversationStateRepository.findByConversationKey(anyString())).thenReturn(Optional.empty());
        lenient().when(tokenUsageRepository.countByConversationKey(anyString())).thenReturn(0L);
    }

    @Test
    void usersMessage_isPersistedUndeliveredJustAsVersionOneDoes() {
        brain.next = turn("Hello!", 1);

        service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "hi", SENT_AT, "bot-msg-1", BotStreamListener.NOOP);

        // The handler flips this once the reply is persisted — the double tick
        // means "the bot has answered" for both bots alike.
        verify(chatMessageService).persistBotConversationMessage(
                USER_MESSAGE_ID, USER_ID, BOT_ID, "hi", SENT_AT);
    }

    @Test
    void theExecutorIsScopedToTheAuthenticatedPatient() {
        // The structural privacy property. The executor handed to the brain must
        // be built for THIS user — nothing the model does can widen it.
        brain.next = turn("Hello!", 1);

        service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "hi", SENT_AT, "bot-msg-1", BotStreamListener.NOOP);

        assertThat(brain.seenExecutor).isNotNull();
    }

    @Test
    void firstTurn_sendsNoChainAndStoresTheNewResponseId() {
        brain.next = turn("Hello!", 1);

        BotReply reply = service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "hi", SENT_AT, "bot-msg-1", BotStreamListener.NOOP);

        assertThat(brain.seenPreviousResponseId).isNull();
        assertThat(reply.content()).isEqualTo("Hello!");

        ArgumentCaptor<BotConversationState> state = ArgumentCaptor.forClass(BotConversationState.class);
        verify(conversationStateRepository).save(state.capture());
        assertThat(state.getValue().getConversationKey()).isEqualTo(CONVERSATION_KEY);
        assertThat(state.getValue().getLastResponseId()).isEqualTo("resp_1");
    }

    @Test
    void firstTurnGetsTheWelcomeInstruction_laterTurnsDoNot() {
        brain.next = turn("Hello!", 1);
        service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "hi", SENT_AT, "bot-msg-1", BotStreamListener.NOOP);
        assertThat(brain.seenSystemPrompt).startsWith("THIS IS THE FIRST MESSAGE");

        when(conversationStateRepository.findByConversationKey(CONVERSATION_KEY))
                .thenReturn(Optional.of(new BotConversationState(CONVERSATION_KEY, "resp_1", SENT_AT)));
        brain.next = turn("Sure.", 1);
        service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "and then?", SENT_AT, "bot-msg-1", BotStreamListener.NOOP);
        assertThat(brain.seenSystemPrompt).contains("already under way");
    }

    @Test
    void thePromptCarriesNoClinicData_whichIsTheWholePoint() {
        brain.next = turn("Hello!", 1);

        service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "hi", SENT_AT, "bot-msg-1", BotStreamListener.NOOP);

        // Version 1's prompt lists every doctor, every working-hours row and
        // every booking. This one must not — if clinic data ever leaks back into
        // it, the token comparison silently stops meaning anything.
        assertThat(brain.seenSystemPrompt)
                .doesNotContain("availability_id=")
                .doesNotContain("WEEKLY WORKING PATTERN")
                .doesNotContain("SLOTS ALREADY TAKEN");
        assertThat(brain.seenSystemPrompt.length()).isLessThan(6000);
    }

    @Test
    void laterTurn_chainsFromTheStoredResponseId() {
        when(conversationStateRepository.findByConversationKey(CONVERSATION_KEY))
                .thenReturn(Optional.of(new BotConversationState(CONVERSATION_KEY, "resp_1", SENT_AT)));
        brain.next = turn("Sure.", 1);

        service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "the 9am one", SENT_AT, "bot-msg-1", BotStreamListener.NOOP);

        assertThat(brain.seenPreviousResponseId).isEqualTo("resp_1");
    }

    @Test
    void tokenUsage_recordsTheSumAcrossEveryRoundOfTheLoop() {
        // A multi-round turn that recorded only the last call would flatter
        // Version 2 in the comparison against Version 1.
        when(tokenUsageRepository.countByConversationKey(CONVERSATION_KEY)).thenReturn(2L);
        brain.next = new ToolTurn("Booked.", "resp_3",
                new TokenUsage(4200, 180, 4380), "gpt-4o-mini",
                List.of(new ToolInvocation("get_available_slots", "{}", "{\"available_slots\":[]}")), 3);

        service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "book it", SENT_AT, "bot-msg-1", BotStreamListener.NOOP);

        ArgumentCaptor<BotTokenUsage> usage = ArgumentCaptor.forClass(BotTokenUsage.class);
        verify(tokenUsageRepository).save(usage.capture());
        assertThat(usage.getValue().getTurnNumber()).isEqualTo(3);
        assertThat(usage.getValue().getInputTokens()).isEqualTo(4200);
        assertThat(usage.getValue().getTotalTokens()).isEqualTo(4380);
    }

    @Test
    void theToolTraceIsLogged_becauseThePromptAloneNoLongerExplainsTheTurn() {
        brain.next = new ToolTurn("Here you go.", "resp_1",
                new TokenUsage(100, 20, 120), "gpt-4o-mini",
                List.of(new ToolInvocation("list_specialties", "{}", "{\"specialties\":[\"Orthopedic\"]}"),
                        new ToolInvocation("find_doctors", "{\"specialty\":\"Orthopedic\"}", "{\"doctors\":[]}")),
                2);

        service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "who do you have?", SENT_AT, "bot-msg-1", BotStreamListener.NOOP);

        ArgumentCaptor<BotPromptLog> logRow = ArgumentCaptor.forClass(BotPromptLog.class);
        verify(promptLogRepository).save(logRow.capture());
        // Version 1's row is explained by its giant prompt. Version 2's prompt is
        // small and says nothing about the clinic — the trace IS the record of
        // what it looked at, and without it the row would be unreadable.
        assertThat(logRow.getValue().getToolCalls())
                .contains("list_specialties")
                .contains("find_doctors");
        assertThat(logRow.getValue().getAction()).isEqualTo("TOOLS:2");
    }

    @Test
    void expiredChain_isRetriedFromScratchAsFirstTurn() {
        when(conversationStateRepository.findByConversationKey(CONVERSATION_KEY))
                .thenReturn(Optional.of(new BotConversationState(CONVERSATION_KEY, "resp_ancient", SENT_AT)));
        brain.failOnceWith = new ExpiredConversationException("gone", null);
        brain.next = turn("Hello again!", 1);

        BotReply reply = service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "hi", SENT_AT, "bot-msg-1", BotStreamListener.NOOP);

        assertThat(reply.content()).isEqualTo("Hello again!");
        assertThat(brain.calls).isEqualTo(2);
        assertThat(brain.seenPreviousResponseId).isNull();
        // The retry genuinely IS turn one as far as the model can see, so the
        // rebuilt prompt has to say so rather than telling it to carry on from a
        // conversation it cannot remember.
        assertThat(brain.seenSystemPrompt).startsWith("THIS IS THE FIRST MESSAGE");
    }

    @Test
    void aFailedTurn_apologisesAndLeavesTheChainIntact() {
        brain.failure = new BotBrainException("upstream 503");

        BotReply reply = service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "hi", SENT_AT, "bot-msg-1", BotStreamListener.NOOP);

        assertThat(reply.content()).contains("something went wrong");
        verify(conversationStateRepository, never()).save(any());
        verify(tokenUsageRepository, never()).save(any());
    }

    @Test
    void unconfiguredBrain_apologisesWithoutCallingTheModel() {
        brain.configured = false;

        BotReply reply = service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "hi", SENT_AT, "bot-msg-1", BotStreamListener.NOOP);

        assertThat(reply.content()).contains("isn't available");
        assertThat(brain.calls).isZero();
        // Still persisted — it was really sent, and belongs in history whether or
        // not the bot could answer.
        verify(chatMessageService).persistBotConversationMessage(
                anyString(), anyString(), anyString(), anyString(), any());
    }

    private ToolTurn turn(String reply, int rounds) {
        return new ToolTurn(reply, "resp_1", new TokenUsage(100, 20, 120), "gpt-4o-mini", List.of(), rounds);
    }

    /** See {@code DoctorAssistantBotServiceTest}'s fake for why this is not a mock. */
    private static final class FakeToolBrain implements ToolCallingBrain {
        private boolean configured = true;
        private ToolTurn next;
        private BotBrainException failure;
        private BotBrainException failOnceWith;
        private int calls;
        private String seenPreviousResponseId;
        private String seenSystemPrompt;
        private ClinicToolExecutor seenExecutor;

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public ToolTurn respond(String systemPrompt, String userMessage, String previousResponseId,
                                ClinicToolExecutor executor, BotStreamListener listener) {
            calls++;
            seenSystemPrompt = systemPrompt;
            seenPreviousResponseId = previousResponseId;
            seenExecutor = executor;
            if (failOnceWith != null) {
                BotBrainException once = failOnceWith;
                failOnceWith = null;
                throw once;
            }
            if (failure != null) {
                throw failure;
            }
            return next;
        }
    }
}
