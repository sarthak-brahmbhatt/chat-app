package com.chatapp.chatservice.bot;

import com.chatapp.chatservice.bot.entity.BotConversationState;
import com.chatapp.chatservice.bot.entity.BotTokenUsage;
import com.chatapp.chatservice.bot.entity.Doctor;
import com.chatapp.chatservice.bot.repository.BotConversationStateRepository;
import com.chatapp.chatservice.bot.repository.BotTokenUsageRepository;
import com.chatapp.chatservice.service.ChatMessageService;
import com.chatapp.chatservice.support.ConversationKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * §5.3's turn sequence (CLAUDE.md 3.9).
 *
 * <p>Uses a hand-written fake for {@link BotBrain} rather than mocking the SDK.
 * That seam exists precisely so this sequence — persist, gather, call, validate,
 * reply, record — can be asserted without a billable network call or a pile of
 * stubbed builder types that would only test the SDK.
 */
@ExtendWith(MockitoExtension.class)
class DoctorAssistantBotServiceTest {

    private static final String USER_ID = "42";
    private static final String BOT_ID = "7";
    private static final String USER_MESSAGE_ID = "client-generated-uuid";
    private static final Instant SENT_AT = Instant.parse("2026-08-24T09:00:00Z");
    private static final String CONVERSATION_KEY = ConversationKey.of(USER_ID, BOT_ID);

    @Mock
    private ClinicDataProvider clinicDataProvider;

    @Mock
    private BotPromptBuilder promptBuilder;

    @Mock
    private BookingService bookingService;

    @Mock
    private ChatMessageService chatMessageService;

    @Mock
    private BotConversationStateRepository conversationStateRepository;

    @Mock
    private BotTokenUsageRepository tokenUsageRepository;

    private FakeBotBrain brain;
    private DoctorAssistantBotService service;

    @BeforeEach
    void setUp() {
        brain = new FakeBotBrain();
        Clock fixed = Clock.fixed(SENT_AT, ZoneOffset.UTC);
        service = new DoctorAssistantBotService(
                brain, clinicDataProvider, promptBuilder, bookingService, chatMessageService,
                conversationStateRepository, tokenUsageRepository, fixed);

        lenient().when(clinicDataProvider.snapshot()).thenReturn(snapshotWithDoctors());
        lenient().when(promptBuilder.systemPrompt(any(), anyBoolean())).thenReturn("SYSTEM PROMPT");
        lenient().when(conversationStateRepository.findByConversationKey(anyString())).thenReturn(Optional.empty());
        lenient().when(tokenUsageRepository.countByConversationKey(anyString())).thenReturn(0L);
    }

    @Test
    void usersMessage_isPersistedDirectlyAndNotYetDelivered() {
        brain.next = turn(decision("Hello!", BotAction.NONE), "resp_1");

        service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "hi", SENT_AT);

        // Undelivered on arrival. In a bot conversation the double tick means
        // "the bot has answered", so the handler flips this once the reply is
        // persisted — inserting it pre-delivered would make a refresh during the
        // model call show a double tick for an answer that does not exist yet.
        verify(chatMessageService).persistBotConversationMessage(
                USER_MESSAGE_ID, USER_ID, BOT_ID, "hi", SENT_AT);
    }

    @Test
    void expiredConversationChain_isRetriedFromScratchRatherThanFailing() {
        // OpenAI does not retain conversation state forever, so a conversation
        // resumed days later presents an id the API no longer knows. That is the
        // ordinary fate of an idle conversation, not a fault, and must not reach
        // the user as an error.
        when(conversationStateRepository.findByConversationKey(CONVERSATION_KEY))
                .thenReturn(Optional.of(new BotConversationState(CONVERSATION_KEY, "resp_ancient", SENT_AT)));
        brain.failOnceWith = new ExpiredConversationException("resp_ancient is gone", null);
        brain.next = turn(decision("Hi! How can I help?", BotAction.NONE), "resp_fresh");

        BotReply reply = service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "hi again", SENT_AT);

        assertThat(reply.content()).isEqualTo("Hi! How can I help?");
        assertThat(brain.calls).isEqualTo(2);
        // The retry drops the stale id — that is what starts a fresh chain.
        assertThat(brain.seenPreviousResponseId).isNull();
    }

    @Test
    void afterAnExpiredChain_theFreshResponseIdReplacesTheStaleOne() {
        // Self-healing: without this the stale id would be presented again next
        // turn, and every turn would silently cost two API calls.
        when(conversationStateRepository.findByConversationKey(CONVERSATION_KEY))
                .thenReturn(Optional.of(new BotConversationState(CONVERSATION_KEY, "resp_ancient", SENT_AT)));
        brain.failOnceWith = new ExpiredConversationException("resp_ancient is gone", null);
        brain.next = turn(decision("Hi!", BotAction.NONE), "resp_fresh");

        service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "hi again", SENT_AT);

        ArgumentCaptor<BotConversationState> state = ArgumentCaptor.forClass(BotConversationState.class);
        verify(conversationStateRepository).save(state.capture());
        assertThat(state.getValue().getLastResponseId()).isEqualTo("resp_fresh");
    }

    @Test
    void expiryRetryIsAttemptedOnce_andASecondFailureApologises() {
        when(conversationStateRepository.findByConversationKey(CONVERSATION_KEY))
                .thenReturn(Optional.of(new BotConversationState(CONVERSATION_KEY, "resp_ancient", SENT_AT)));
        brain.failOnceWith = new ExpiredConversationException("resp_ancient is gone", null);
        brain.failure = new BotBrainException("upstream 503");

        BotReply reply = service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "hi again", SENT_AT);

        assertThat(reply.content()).contains("something went wrong");
        assertThat(brain.calls).isEqualTo(2);
        verify(conversationStateRepository, never()).save(any());
    }

    @Test
    void firstTurn_sendsNoPreviousResponseIdAndStoresTheNewOne() {
        brain.next = turn(decision("Hello!", BotAction.NONE), "resp_1");

        BotReply reply = service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "hi", SENT_AT);

        assertThat(brain.seenPreviousResponseId).isNull();
        assertThat(reply.content()).isEqualTo("Hello!");

        ArgumentCaptor<BotConversationState> state = ArgumentCaptor.forClass(BotConversationState.class);
        verify(conversationStateRepository).save(state.capture());
        assertThat(state.getValue().getConversationKey()).isEqualTo(CONVERSATION_KEY);
        assertThat(state.getValue().getLastResponseId()).isEqualTo("resp_1");
    }

    @Test
    void laterTurn_chainsFromTheStoredResponseId() {
        // Without this the bot restarts from nothing on every message and asks
        // "which doctor?" immediately after being told.
        when(conversationStateRepository.findByConversationKey(CONVERSATION_KEY))
                .thenReturn(Optional.of(new BotConversationState(CONVERSATION_KEY, "resp_1", SENT_AT)));
        brain.next = turn(decision("Sure.", BotAction.NONE), "resp_2");

        service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "the 9am one", SENT_AT);

        assertThat(brain.seenPreviousResponseId).isEqualTo("resp_1");
    }

    @Test
    void tokenUsage_isRecordedWithAnIncrementingTurnNumber() {
        when(tokenUsageRepository.countByConversationKey(CONVERSATION_KEY)).thenReturn(3L);
        brain.next = new BotTurn(decision("Hi", BotAction.NONE), "resp_4",
                new TokenUsage(1500, 40, 1540), "gpt-4o-mini");

        BotReply reply = service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "hi", SENT_AT);

        ArgumentCaptor<BotTokenUsage> usage = ArgumentCaptor.forClass(BotTokenUsage.class);
        verify(tokenUsageRepository).save(usage.capture());
        assertThat(usage.getValue().getTurnNumber()).isEqualTo(4);
        assertThat(usage.getValue().getInputTokens()).isEqualTo(1500);
        assertThat(usage.getValue().getTotalTokens()).isEqualTo(1540);
        assertThat(usage.getValue().getModel()).isEqualTo("gpt-4o-mini");
        // Tied to the reply it produced, so a row can be lined up against the
        // text it paid for.
        assertThat(usage.getValue().getMessageId()).isEqualTo(reply.messageId());
    }

    @Test
    void bookDecision_isValidatedAndTheBookingServicesReplyWins() {
        brain.next = turn(decision("Booked!", BotAction.BOOK, 7L, "2026-08-24"), "resp_1");
        when(bookingService.book(any(), eq(42L)))
                .thenReturn(BookingOutcome.rejected("Sorry — that slot isn't available any more."));

        BotReply reply = service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "yes please", SENT_AT);

        // §6.4: on a failed booking the model's confirmation is discarded
        // entirely. Sending "Booked!" here would tell the user they have an
        // appointment that was never created.
        assertThat(reply.content()).doesNotContain("Booked!");
        assertThat(reply.content()).contains("isn't available");
    }

    @Test
    void noneDecision_neverReachesTheBookingService() {
        brain.next = turn(decision("Which day suits you?", BotAction.NONE), "resp_1");

        service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "I need a cardiologist", SENT_AT);

        verifyNoInteractions(bookingService);
    }

    @Test
    void unconfiguredBrain_apologisesWithoutCallingTheModel() {
        brain.configured = false;

        BotReply reply = service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "hi", SENT_AT);

        assertThat(reply.content()).contains("isn't available");
        assertThat(brain.calls).isZero();
        // The user's message is still persisted — it was really sent, and it has
        // to be in history whether or not the bot could answer.
        verify(chatMessageService).persistBotConversationMessage(
                anyString(), anyString(), anyString(), anyString(), any());
        verify(tokenUsageRepository, never()).save(any());
    }

    @Test
    void failedModelCall_apologisesAndLeavesConversationStateUntouched() {
        brain.failure = new BotBrainException("upstream 503");

        BotReply reply = service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "hi", SENT_AT);

        assertThat(reply.content()).contains("something went wrong");
        // The last GOOD response id must survive, so the next turn still chains
        // onto a coherent history instead of the failure silently wiping the
        // conversation's memory.
        verify(conversationStateRepository, never()).save(any());
        verify(tokenUsageRepository, never()).save(any());
    }

    @Test
    void noActiveDoctors_shortCircuitsBeforeSpendingACall() {
        // A model handed an empty dataset is being invited to invent a doctor,
        // and there is no id it could legitimately book anyway.
        when(clinicDataProvider.snapshot()).thenReturn(emptySnapshot());

        BotReply reply = service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "book me in", SENT_AT);

        assertThat(reply.content()).contains("no doctors");
        assertThat(brain.calls).isZero();
    }

    @Test
    void everyReplyCarriesAServerGeneratedMessageId() {
        // The bot has no client to mint one (CLAUDE.md 3.1's client-generated id
        // assumes a browser rendering an optimistic bubble), so this side has to.
        brain.next = turn(decision("Hello!", BotAction.NONE), "resp_1");

        BotReply first = service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "hi", SENT_AT);
        brain.next = turn(decision("Hello again!", BotAction.NONE), "resp_2");
        BotReply second = service.handleUserMessage(USER_ID, BOT_ID, USER_MESSAGE_ID, "hi", SENT_AT);

        assertThat(first.messageId()).isNotBlank().isNotEqualTo(second.messageId());
    }

    private ClinicSnapshot snapshotWithDoctors() {
        return new ClinicSnapshot(
                LocalDate.of(2026, 8, 24), LocalTime.of(9, 0),
                List.of("Orthopedic"), List.of(new Doctor("Dr. Tushar Mehta", "Orthopedic")),
                List.of(), List.of(), LocalDate.of(2026, 8, 30));
    }

    private ClinicSnapshot emptySnapshot() {
        return new ClinicSnapshot(
                LocalDate.of(2026, 8, 24), LocalTime.of(9, 0),
                List.of(), List.of(), List.of(), List.of(), LocalDate.of(2026, 8, 30));
    }

    private BotDecision decision(String reply, BotAction action) {
        return new BotDecision(reply, action, Optional.empty(), Optional.empty());
    }

    private BotDecision decision(String reply, BotAction action, Long availabilityId, String date) {
        return new BotDecision(reply, action, Optional.of(availabilityId), Optional.of(date));
    }

    private BotTurn turn(BotDecision decision, String responseId) {
        return new BotTurn(decision, responseId, new TokenUsage(100, 20, 120), "gpt-4o-mini");
    }

    /**
     * A fake rather than a Mockito mock: these tests care what the brain was
     * ASKED (the chained response id especially) as much as what it returned, and
     * a two-field fake reads better than a captor per assertion.
     */
    private static final class FakeBotBrain implements BotBrain {
        private boolean configured = true;
        private BotTurn next;
        private BotBrainException failure;
        // Thrown on the FIRST call only, then cleared — models the expired-chain
        // case, where the retry is expected to succeed. A plain `failure` would
        // throw on the retry too and prove nothing about recovery.
        private BotBrainException failOnceWith;
        private int calls;
        private String seenPreviousResponseId;

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public BotTurn respond(String systemPrompt, String userMessage, String previousResponseId) {
            calls++;
            seenPreviousResponseId = previousResponseId;
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
