# Fix message-delivery gaps: reconnect sweep + Kafka-ordered delivery

## Context

Two confirmed bugs in how `messages.delivered` gets set, both traced against
the actual current code (full trace with citations: `docs/current-delivery-flow.md`):

- **Bug A — recipient offline at send time.** `deliverIfRecipientConnected`
  finds no live session, so `incoming_message` is never sent, so the
  frontend's only `delivered_ack` trigger (`chat.service.ts:118-120`, fires
  only inside the live WebSocket listener) never runs. `markDelivered` is
  never called. The row stays `delivered=false` forever — reopening the
  chat later doesn't fix it, since `GET /conversations/.../messages` is a
  pure read.
- **Bug B — a real, empirically-confirmed race.** Even when the recipient
  IS online, `handleDeliveredAck`'s direct synchronous `UPDATE` (via
  `ChatMessageService.markDelivered`) has no Kafka hop and is almost always
  faster than Kafka's own publish→poll→`INSERT` path (`ChatMessageConsumer`).
  The `UPDATE` matches zero rows, is logged, never retried. The row is then
  inserted `delivered=false` by the consumer, permanently.

Discussed and clarified with the user directly:

- Bug A's fix: **reconnect-time sweep** — when a user's WebSocket
  authenticates, mark all of their pending `delivered=false` messages
  delivered, and live-notify the original sender if reachable.
- Bug B's fix, per the user's own proposal after walking through the
  residual gap of a pure reconnect-sweep (a continuously-connected recipient
  who never disconnects would stay stuck until *some* unrelated reconnect
  eventually happens): **route the delivery write through Kafka itself**, on
  the *same topic and same partition key* as the original message. Kafka's
  per-partition ordering guarantee then makes the ordering *structural*, not
  probabilistic — a `delivered_ack` can only ever be produced after the
  original message event was already produced (causally impossible
  otherwise), and both landing on the same partition guarantees the consumer
  processes the insert before the delivered-update, every time. This isn't a
  mitigation — it eliminates Bug B outright, and the existing "accepted race"
  language in `ChatMessageService.markDelivered`'s Javadoc and CLAUDE.md §4
  becomes stale and must be corrected, not just supplemented.

Together, both parts close the entire gap the user described: "when user is
online and establishes a WebSocket connection, all messages get through the
API, and if delivered, the sender should see double tick" — Part 1 for the
truly-never-delivered case, Part 2 for the delivered-but-lost-the-write-race
case, with no probabilistic timing anywhere in the design.

## Part 1 — Reconnect-time delivery sweep (fixes Bug A)

**New repository method** — `ChatMessageRepository.java`, a plain
Spring-Data derived query (no `@Query` needed, same mechanism as the
existing `existsByMessageId`):

```java
List<ChatMessage> findByRecipientIdAndDeliveredFalse(String recipientId);
```

A SELECT-first shape is required (not a direct bulk UPDATE by recipientId)
because the per-row `messageId`/`senderId` pairs are needed afterward to
know who to live-notify — an UPDATE's rows-affected count can't reconstruct
that.

**New `ChatMessageService` method:**

```java
public List<PendingDeliveryNotification> sweepUndeliveredForRecipient(String recipientId) {
    List<ChatMessage> undelivered = chatMessageRepository.findByRecipientIdAndDeliveredFalse(recipientId);
    if (undelivered.isEmpty()) {
        return List.of();
    }
    List<PendingDeliveryNotification> notifications = new ArrayList<>();
    for (ChatMessage message : undelivered) {
        markDelivered(message.getMessageId());   // reuses the existing method — see note below
        notifications.add(new PendingDeliveryNotification(message.getMessageId(), message.getSenderId()));
    }
    return notifications;
}
```

Calls the service's *own* `markDelivered(String)` (not the repository
directly) — this keeps `ChatMessageService.markDelivered` as the single
canonical place that performs and logs a delivery write, which matters once
Part 2 also needs a "mark this delivered" operation (see Part 2's dependency
note for why the consumer path deliberately does *not* go through this
method, to stay consistent with `ChatMessageConsumer`'s existing
repository-only pattern).

Not this pass: N single-row UPDATEs for a large backlog is an accepted,
named tradeoff (an internal ~10k-user tool won't realistically produce a
large per-user backlog) — a future `markDeliveredBatch(List<String>)` via
`WHERE messageId IN (:ids)` is the contained follow-up if this is ever
shown to matter, not something to build speculatively now.

**New record** — `chat-service/src/main/java/com/chatapp/chatservice/service/PendingDeliveryNotification.java`:
```java
package com.chatapp.chatservice.service;

public record PendingDeliveryNotification(String messageId, String senderId) {
}
```
Lives in `service/`, not `dto/` — everything in `dto/` today is a literal
wire-protocol JSON shape (documented in CLAUDE.md §3.1); this is never
serialized as-is, it's translated into a `TickAck` before touching a socket.

**Wiring in `ChatWebSocketHandler`:**

a) Extract the existing live-double-tick-send block out of
`handleDeliveredAck` (current lines 279-291 — the `Optional` lookup, the
empty check, and the `try`/`catch IOException` send) into a shared private
helper, since Part 1's sweep needs the exact same "notify sender if
connected" logic:

```java
private void sendDoubleTickIfConnected(String senderId, String messageId) {
    Optional<WebSocketSession> senderSession = connectionRegistry.find(senderId);
    if (senderSession.isEmpty()) {
        log.info("Sender {} not connected; live double-tick for message {} not sent (delivery was still persisted)",
                senderId, messageId);
        return;
    }
    try {
        TickAck doubleTick = TickAck.doubleTick(messageId);
        senderSession.get().sendMessage(new TextMessage(objectMapper.writeValueAsString(doubleTick)));
    } catch (IOException e) {
        log.warn("Failed to deliver double-tick for message {} to sender {}: {}", messageId, senderId, e.getMessage());
    }
}
```
Pure extraction — behavior-preserving, existing `handleDeliveredAck` tests
should still pass once its two call sites at the end of that method are
replaced with `sendDoubleTickIfConnected(ack.senderId(), ack.messageId())`.

b) Add the sweep call inside `authenticate()`, right after
`connectionRegistry.register(userId, session)` (current line 148):

```java
connectionRegistry.register(userId, session);
log.info("WebSocket connection {} authenticated as user {}", session.getId(), userId);
notifyPendingDeliveries(userId);
```

```java
/**
 * Sweeps every message where this now-connecting user is the recipient
 * and delivered=false, marks each delivered, and live-notifies whichever
 * original sender happens to be connected right now. Never re-pushes
 * message CONTENT to this session — that stays exclusively the job of
 * GET /conversations/{otherUserId}/messages, already called by the
 * frontend (ChatComponent.ngOnInit) before this connection even opens.
 * Re-sending as incoming_message here would risk a duplicate bubble.
 */
private void notifyPendingDeliveries(String recipientId) {
    List<PendingDeliveryNotification> notifications = chatMessageService.sweepUndeliveredForRecipient(recipientId);
    for (PendingDeliveryNotification notification : notifications) {
        sendDoubleTickIfConnected(notification.senderId(), notification.messageId());
    }
}
```

**Concurrency tradeoff, accepted and named, not solved:** two near-simultaneous
connects for the same recipient (two tabs, or a stale session's
`afterConnectionClosed` racing a fresh one) could both sweep the same rows
and both fire a duplicate live double-tick. Harmless: `TickAck.doubleTick`
is idempotent on the frontend (`chat.component.ts`'s `tickAcks$` handler is
a keyed `.map`, setting the same value twice is a no-op), and a duplicate
`UPDATE ... SET delivered=true` is a no-op the second time. No locking added
— consistent with `ConnectionRegistry`'s own existing no-locking,
single-instance design.

**Frontend: no changes needed.** The pushed `TickAck` is byte-identical to
what already exists on the wire. `chat.component.ts:128-132`'s `tickAcks$`
subscription already matches by `messageId` against whatever's in that
component's `bubbles` signal, with no sender/recipient filtering — if the
sender isn't currently viewing that specific conversation, the update is a
harmless no-op, and they'll see the correct state the next time they
open/reopen it via the ordinary history fetch. Worth stating plainly (in
CLAUDE.md, not oversold): `ChatService`'s WebSocket connection is scoped to
whichever `ChatComponent` is mounted (its own class comment already says
so) — the sender only gets the *live* push if that specific chat happens to
be open at that moment; otherwise it's correct-on-next-view, not instant.

## Part 2 — Kafka-ordered delivery write (fixes Bug B structurally)

**New sealed interface** — `chat-service/src/main/java/com/chatapp/chatservice/kafka/ChatTopicEvent.java`:
```java
package com.chatapp.chatservice.kafka;

public sealed interface ChatTopicEvent permits ChatMessageEvent, MessageDeliveredEvent {
}
```

**`ChatMessageEvent.java`** — add `implements ChatTopicEvent` to the existing
record declaration. No field changes.

**New record** — `chat-service/src/main/java/com/chatapp/chatservice/kafka/MessageDeliveredEvent.java`:
```java
package com.chatapp.chatservice.kafka;

public record MessageDeliveredEvent(String messageId) implements ChatTopicEvent {
}
```
Minimal on purpose — the consumer only needs the id to run the existing
`markDelivered(messageId)` bulk update; no speculative extra fields.

**`ChatMessagePublisher.java` changes:**
- Field type widens: `KafkaTemplate<String, ChatMessageEvent>` →
  `KafkaTemplate<String, ChatTopicEvent>`. (Spring Boot's auto-configured
  `KafkaTemplate` bean is generically loose enough at the injection point
  for this to resolve the same underlying bean — no new `@Bean` needed. No
  `spring.json.value.default.type` is configured today, so `JsonDeserializer`
  already resolves concrete types per-record via the `__TypeId__` header
  `JsonSerializer` adds by default — this is the standard Spring Kafka
  mechanism for a multi-type topic, not a new config surface.)
- Existing `publish(senderId, request)`: unchanged logic.
- New method, same non-blocking `.whenComplete`-for-logging-only /
  synchronous-`RuntimeException`-catch shape as `publish()`:
```java
public void publishDelivered(String senderId, String recipientId, String messageId) {
    try {
        String key = conversationKey(senderId, recipientId);
        MessageDeliveredEvent event = new MessageDeliveredEvent(messageId);
        kafkaTemplate.send(KafkaTopicConfig.CHAT_MESSAGES_TOPIC, key, event)
                .whenComplete((result, exception) -> { /* same logging pattern as publish() */ });
    } catch (RuntimeException e) {
        // same "never let a Kafka problem propagate" reasoning as publish()
    }
}
```
Reuses the existing package-private `conversationKey(...)` — **same key as
the original message**, which is the entire mechanism this fix relies on.

**`ChatMessageConsumer.java` changes:**
- `consume(...)` parameter widens from `ChatMessageEvent` to `ChatTopicEvent`.
- Body becomes a pattern-matching switch (Java 21):
```java
@KafkaListener(topics = KafkaTopicConfig.CHAT_MESSAGES_TOPIC, groupId = "${spring.kafka.consumer.group-id}")
public void consume(ChatTopicEvent event) {
    switch (event) {
        case ChatMessageEvent sent -> persistNewMessage(sent);
        case MessageDeliveredEvent delivered -> chatMessageRepository.markDelivered(delivered.messageId());
    }
}

private void persistNewMessage(ChatMessageEvent event) {
    // exact existing body: existsByMessageId guard + save
}
```
**No new constructor dependency** — `ChatMessageConsumer` keeps its existing
sole dependency on `ChatMessageRepository` and calls `markDelivered`
directly on it, matching its own existing pattern exactly (it already calls
`existsByMessageId`/`save` directly, never through `ChatMessageService`).
This is a deliberate choice over routing through `ChatMessageService`: it
keeps the consumer's dependency graph unchanged and mirrors how it already
treats persistence as its own direct concern.

**`ChatWebSocketHandler.handleDeliveredAck` changes:**
```java
private void handleDeliveredAck(WebSocketSession session, String payload) {
    // ... existing parse + blank-check unchanged ...

    String recipientId = (String) session.getAttributes().get(USER_ID_ATTRIBUTE);
    chatMessagePublisher.publishDelivered(ack.senderId(), recipientId, ack.messageId());  // async, ordered
    sendDoubleTickIfConnected(ack.senderId(), ack.messageId());                            // unchanged: live, instant
}
```
The live double-tick stays synchronous and immediate — this only changes
*persistence*, mirroring the exact same "decouple confirming receipt from
durably writing" reasoning CLAUDE.md §3.4 already uses for the original
single-tick/Kafka-insert split. `chatMessageService.markDelivered` is no
longer called from here at all.

**Consequence for `ChatMessageService.markDelivered`:** after this change,
its only caller is Part 1's sweep. Its Javadoc and zero-rows log message
currently describe the *old* race ("routinely the case for a fast
delivered_ack, not rare") — that framing is now false and must be rewritten
to describe the *new*, much rarer cause of a zero-rows hit here: the
Part-1 concurrent-double-sweep race (§ above), not a Kafka-ordering race
(which no longer exists).

## CLAUDE.md updates

- **§3.1**, the `delivered_ack`/disconnected-sender bullets: revise to
  describe the reconnect-sweep (Part 1) and correct the "with no
  offline-message-queue (explicitly out of scope, section 5)" line, which
  is stale on two counts: §5's actual bullet list has no such line today
  (a pre-existing cross-reference error, fix it), and the claim itself
  stops being true once this ships.
- **§5**: add a precisely-scoped bullet for what's still *not* built —
  message *content* is never re-pushed live to a reconnecting recipient,
  only delivery status; content delivery remains exclusively the
  `GET /conversations` history fetch.
- **§4**: the existing "Delivered-flag persistence race (accepted, not
  retried)" bullet must be **replaced**, not appended to — it describes a
  race that Part 2 eliminates structurally. New text should explain: the
  Kafka-same-partition-key ordering guarantee and why it's structural, not
  probabilistic; the reconnect-sweep and why it also incidentally covers
  any legacy stuck rows from before this fix shipped; the still-accepted
  small concurrency tradeoff from Part 1 (harmless duplicate ticks/UPDATEs
  on near-simultaneous reconnects). Leaving the old "accepted race" language
  in place after fixing it would itself be stale/misleading documentation.

## Tests

**`ChatMessageServiceTest.java`** (existing conventions: inline
`ChatMessageService` construction per test, reflection-based `message(...)`
fixture builder, AssertJ `extracting(...)`):
- `sweepUndeliveredForRecipient_withPendingMessages_marksEachDeliveredAndReturnsSenderPairs`
- `sweepUndeliveredForRecipient_withNoPendingMessages_returnsEmptyListWithoutTouchingRepository`
- Update the two existing `markDelivered` tests' Javadoc/comments if they
  reference the old "Kafka race" framing.

**`ChatWebSocketHandlerTest.java`** (existing conventions: real
`ConnectionRegistry`/`JwtValidator`, mocked `ChatMessageService`, the
`authenticatedSession(String userId)` helper):
- Required `setUp()` addition: `lenient().when(chatMessageService.sweepUndeliveredForRecipient(anyString())).thenReturn(List.of());`
  — every existing test that calls `authenticatedSession()` now triggers
  `authenticate()`'s new sweep call; without this default stub, the mock
  returns `null` and the sweep's loop NPEs, breaking ~10 unrelated existing tests.
- `authenticate_withPendingUndeliveredMessages_notifiesConnectedOriginalSender`
- `authenticate_withPendingUndeliveredMessages_originalSenderNotConnected_doesNotThrow`
- `authenticate_withNoPendingUndeliveredMessages_doesNotSendAnythingExtra`
- Existing `handleTextMessage_deliveredAckForConnectedSender_...` and
  `...ForDisconnectedSender_...` tests: change their assertion from
  `verify(chatMessageService).markDelivered(...)` to verifying
  `chatMessagePublisher.publishDelivered(senderId, recipientId, messageId)`
  was called — either by mocking `ChatMessagePublisher` directly, or (to
  minimize structural change to this file, since `ChatMessagePublisher` is
  currently real-backed-by-a-mocked-`kafkaTemplate`) capturing the
  `kafkaTemplate.send(...)` call's key/value and asserting a
  `MessageDeliveredEvent` with the right `messageId` was sent to the right
  conversation key. Prefer the latter — smaller diff, no new mock wiring.

**`ChatMessagePublisherTest.java`**: mock field type
`KafkaTemplate<String, ChatMessageEvent>` → `KafkaTemplate<String, ChatTopicEvent>`
(cosmetic, existing tests' logic unchanged). New tests:
- `publishDelivered_sendsMessageDeliveredEventToSameConversationKeyAsOriginalMessage`
  (mirrors `publish_sendsToChatMessagesTopicWithCorrectEventContent`)
- `publishDelivered_neverBlocksEvenWhenKafkaNeverResponds`
- `publishDelivered_swallowsASynchronousKafkaException`

**`ChatMessageConsumerTest.java`**: existing tests unchanged (the insert
path's logic didn't move, just got wrapped in `persistNewMessage`). New:
- `consume_withMessageDeliveredEvent_marksMessageDelivered` — verify
  `chatMessageRepository.markDelivered("m-1")` called.

**`KafkaEndToEndTest.java` — the critical new coverage.** This is the only
place that can actually prove the ordering guarantee holds through a real
broker, not a mock:
```java
@Test
void deliveredEventPublishedAfterMessageEvent_isProcessedInOrder() {
    when(chatMessageRepository.existsByMessageId(anyString())).thenReturn(false);
    MessageListenerContainer container = endpointRegistry.getListenerContainers().iterator().next();
    ContainerTestUtils.waitForAssignment(container, 3);

    chatMessagePublisher.publish("42", new ChatMessageRequest("message", "m-order-1", "99", "hello"));
    chatMessagePublisher.publishDelivered("42", "99", "m-order-1");

    InOrder inOrder = inOrder(chatMessageRepository);
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
        inOrder.verify(chatMessageRepository).save(argThat(m -> m.getMessageId().equals("m-order-1")));
        inOrder.verify(chatMessageRepository).markDelivered("m-order-1");
    });
}
```

## Manual dual-mode verification (Docker + native, per project convention)

**Bug A (recipient never connected at send time):**
1. A sends to B while B has no WebSocket connection at all. Confirm single
   tick, and confirm the messagedb row is `delivered=0`.
2. B connects (opens the chat). Confirm the row flips to `delivered=1`
   immediately, and A gets a live double-tick if A's chat with B happens to
   be open, or sees it correctly on next open otherwise.
3. Repeat natively.

**Bug B (race that used to exist, now structurally impossible):**
1. A and B both online, A sends, B's `delivered_ack` fires fast. Confirm —
   this is the actual proof — the messagedb row shows `delivered=1` shortly
   after, every time, not intermittently. Repeat several times back-to-back
   to build confidence this isn't just a lucky ordering (though the fix is
   structural, not probabilistic, so it should be 100% consistent, unlike
   the old behavior).
2. Repeat natively.

**Regression check:** run the full `./gradlew test` suite; confirm the
existing `ChatWebSocketHandlerTest`/`ChatMessageServiceTest`/
`ChatMessageConsumerTest`/`ChatMessagePublisherTest`/`KafkaEndToEndTest`
suites all still pass with the updated assertions above.

### Critical files
- `chat-service/src/main/java/com/chatapp/chatservice/websocket/ChatWebSocketHandler.java`
- `chat-service/src/main/java/com/chatapp/chatservice/service/ChatMessageService.java`
- `chat-service/src/main/java/com/chatapp/chatservice/service/PendingDeliveryNotification.java` (new)
- `chat-service/src/main/java/com/chatapp/chatservice/repository/ChatMessageRepository.java`
- `chat-service/src/main/java/com/chatapp/chatservice/kafka/ChatMessagePublisher.java`
- `chat-service/src/main/java/com/chatapp/chatservice/kafka/ChatMessageConsumer.java`
- `chat-service/src/main/java/com/chatapp/chatservice/kafka/ChatMessageEvent.java`
- `chat-service/src/main/java/com/chatapp/chatservice/kafka/ChatTopicEvent.java` (new)
- `chat-service/src/main/java/com/chatapp/chatservice/kafka/MessageDeliveredEvent.java` (new)
- `chat-service/src/test/java/com/chatapp/chatservice/websocket/ChatWebSocketHandlerTest.java`
- `chat-service/src/test/java/com/chatapp/chatservice/service/ChatMessageServiceTest.java`
- `chat-service/src/test/java/com/chatapp/chatservice/kafka/ChatMessagePublisherTest.java`
- `chat-service/src/test/java/com/chatapp/chatservice/kafka/ChatMessageConsumerTest.java`
- `chat-service/src/test/java/com/chatapp/chatservice/kafka/KafkaEndToEndTest.java`
- `/Users/sarthak/chat-app/CLAUDE.md`
