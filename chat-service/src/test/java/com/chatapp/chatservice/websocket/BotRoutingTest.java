package com.chatapp.chatservice.websocket;

import com.chatapp.chatservice.bot.routing.BotDirectory;
import com.chatapp.chatservice.bot.routing.BotReply;
import com.chatapp.chatservice.bot.promptstuffing.DoctorAssistantBotService;
import com.chatapp.chatservice.bot.toolcalling.ToolCallingBotService;
import com.chatapp.chatservice.dto.IncomingChatMessage;
import com.chatapp.chatservice.entity.UserType;
import com.chatapp.chatservice.dto.TickAck;
import com.chatapp.chatservice.kafka.ChatMessagePublisher;
import com.chatapp.chatservice.security.JwtValidator;
import com.chatapp.chatservice.service.ChatMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The routing branch (CLAUDE.md 3.9 §5.2): what changes, and what pointedly
 * doesn't, when the recipient is the bot.
 *
 * <p>Kept separate from ChatWebSocketHandlerTest, which predates the bot and
 * stubs {@code isBot} to false throughout so its tests keep covering the
 * human-to-human path they were written for.
 */
@ExtendWith(MockitoExtension.class)
class BotRoutingTest {

    private static final String SECRET = "test-only-bot-routing-secret-at-least-32-bytes-long-xyz";
    private static final String USER_ID = "42";
    private static final String BOT_ID = "7";

    @Mock
    private KafkaTemplate<String, com.chatapp.chatservice.kafka.ChatTopicEvent> kafkaTemplate;

    @Mock
    private ChatMessageService chatMessageService;

    @Mock
    private BotDirectory botDirectory;

    @Mock
    private DoctorAssistantBotService doctorAssistantBotService;

    @Mock
    private ToolCallingBotService toolCallingBotService;

    @Mock
    private WebSocketSession session;

    private ChatWebSocketHandler handler;
    private ObjectMapper objectMapper;
    private Map<String, Object> sessionAttributes;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        JwtValidator jwtValidator = new JwtValidator(SECRET);

        lenient().when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(new CompletableFuture<>());
        lenient().when(chatMessageService.sweepUndeliveredForRecipient(anyString())).thenReturn(List.of());
        // lenient(): the one test about the human path never asks about BOT_ID,
        // so strict stubbing would fail it for an "unused" stub that every other
        // test in the class needs.
        //
        // botKindOf, not isBot: with two bots answering, the handler needs to
        // know WHICH one, so a boolean is no longer enough to route on.
        lenient().when(botDirectory.botKindOf(BOT_ID)).thenReturn(Optional.of(UserType.BOT));

        handler = new ChatWebSocketHandler(
                jwtValidator, new ConnectionRegistry(), objectMapper,
                new ChatMessagePublisher(kafkaTemplate), chatMessageService,
                botDirectory, doctorAssistantBotService, toolCallingBotService);

        sessionAttributes = new HashMap<>();
        when(session.getAttributes()).thenReturn(sessionAttributes);
    }

    @Test
    void messageToTheBot_bypassesKafkaEntirely() throws IOException {
        stubBotReply("Hello! How can I help?");
        authenticate();

        handler.handleTextMessage(session, new TextMessage(chatMessage("m1", BOT_ID, "hi")));

        // §5.3's direct-write decision. Kafka exists to decouple acknowledging a
        // message from durably storing it, which matters when the recipient may
        // be offline — the bot never is, and it has to read the conversation it
        // is part of within the same turn.
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void messageToTheBot_getsBothTicksWithoutAnyDeliveredAck() throws IOException {
        stubBotReply("Hello! How can I help?");
        authenticate();

        handler.handleTextMessage(session, new TextMessage(chatMessage("m1", BOT_ID, "hi")));

        List<TickAck> ticks = sentMessages().stream()
                .map(this::readTick)
                .filter(t -> t != null && "ack".equals(t.type()))
                .toList();

        // Both ticks, with no delivered_ack anywhere — the bot has no browser to
        // send one. What each MEANS is the subject of the two tests below.
        assertThat(ticks).extracting(TickAck::tick).containsExactly("single", "double");
        assertThat(ticks).allSatisfy(t -> assertThat(t.messageId()).isEqualTo("m1"));
    }

    @Test
    void doubleTick_isSentOnlyAfterTheBotsReplyIsPersisted() throws IOException {
        // The whole point of the two ticks meaning different things: single is
        // "the bot got it", double is "the bot has answered". Firing them
        // together, as the literal human-conversation translation would, gives
        // the user two ticks a millisecond apart that say nothing.
        stubBotReply("Here are some times.");
        authenticate();

        InOrder inOrder = inOrder(doctorAssistantBotService, chatMessageService, session);

        handler.handleTextMessage(session, new TextMessage(chatMessage("m1", BOT_ID, "hi")));

        inOrder.verify(doctorAssistantBotService).handleUserMessage(
                anyString(), anyString(), anyString(), anyString(), any(Instant.class));
        inOrder.verify(chatMessageService).persistBotConversationMessage(
                eq("bot-msg-1"), anyString(), anyString(), anyString(), any(Instant.class));
        inOrder.verify(chatMessageService).markDelivered("m1");
        inOrder.verify(session).sendMessage(argThat(this::isDoubleTick));
    }

    @Test
    void usersMessageRow_isMarkedDeliveredAtTheSameMomentAsTheDoubleTick() throws IOException {
        // Keeps history honest. If the row were written delivered on arrival, a
        // refresh during the model call would render a double tick for an answer
        // that does not exist yet — the open socket and the reloaded page would
        // disagree about the same message.
        stubBotReply("Sure.");
        authenticate();

        handler.handleTextMessage(session, new TextMessage(chatMessage("m1", BOT_ID, "hi")));

        verify(chatMessageService).markDelivered("m1");
    }

    @Test
    void botsReply_arrivesAsAnOrdinaryIncomingMessageFromTheBotsUserId() throws IOException {
        stubBotReply("We have Dr. Mehta free at 9am.");
        authenticate();

        handler.handleTextMessage(session, new TextMessage(chatMessage("m1", BOT_ID, "any orthopedists?")));

        IncomingChatMessage incoming = sentMessages().stream()
                .map(this::readIncoming)
                .filter(m -> m != null && "incoming_message".equals(m.type()))
                .findFirst()
                .orElseThrow();

        // Deliberately indistinguishable from a human's message on the wire —
        // that is what lets the frontend carry no bot-specific code at all.
        assertThat(incoming.senderId()).isEqualTo(BOT_ID);
        assertThat(incoming.content()).isEqualTo("We have Dr. Mehta free at 9am.");
        assertThat(incoming.messageId()).isEqualTo("bot-msg-1");
        assertThat(incoming.sentAt()).isNotNull();
    }

    @Test
    void botsReply_isPersistedAndAckedByTheUsersBrowserAsUsual() throws IOException {
        stubBotReply("Sure.");
        authenticate();

        handler.handleTextMessage(session, new TextMessage(chatMessage("m1", BOT_ID, "hi")));

        // The user DOES have a browser, so this row reaches delivered the ordinary
        // way — their client auto-acks it, exactly as for a human's message.
        verify(chatMessageService).persistBotConversationMessage(
                eq("bot-msg-1"), eq(BOT_ID), eq(USER_ID), eq("Sure."), any(Instant.class));
    }

    @Test
    void messageToAHuman_isUnaffectedByTheBranch() throws IOException {
        when(botDirectory.botKindOf("99")).thenReturn(Optional.empty());
        authenticate();

        handler.handleTextMessage(session, new TextMessage(chatMessage("m1", "99", "hey")));

        verify(kafkaTemplate).send(anyString(), anyString(), any());
        verifyNoInteractions(doctorAssistantBotService);
        verify(chatMessageService, never()).persistBotConversationMessage(
                anyString(), anyString(), anyString(), anyString(), any());
    }

    private void stubBotReply(String content) {
        when(doctorAssistantBotService.handleUserMessage(
                anyString(), anyString(), anyString(), anyString(), any(Instant.class)))
                .thenReturn(new BotReply("bot-msg-1", content));
    }

    private void authenticate() throws IOException {
        handler.handleTextMessage(session, new TextMessage(tokenFor(USER_ID)));
    }

    private List<TextMessage> sentMessages() throws IOException {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues();
    }

    private boolean isDoubleTick(TextMessage message) {
        TickAck tick = readTick(message);
        return tick != null && "ack".equals(tick.type()) && "double".equals(tick.tick());
    }

    private TickAck readTick(TextMessage message) {
        try {
            return objectMapper.readValue(message.getPayload(), TickAck.class);
        } catch (Exception e) {
            return null;
        }
    }

    private IncomingChatMessage readIncoming(TextMessage message) {
        try {
            return objectMapper.readValue(message.getPayload(), IncomingChatMessage.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String chatMessage(String messageId, String recipientId, String content) {
        return """
                {"type":"message","messageId":"%s","recipientId":"%s","content":"%s"}"""
                .formatted(messageId, recipientId, content);
    }

    private String tokenFor(String userId) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(key)
                .compact();
    }
}
