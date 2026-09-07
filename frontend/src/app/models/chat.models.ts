/**
 * Mirrors the WebSocket message/ack envelope shapes decided in CLAUDE.md
 * 3.1 (chat-service's dto records: ChatMessageRequest, TickAck,
 * IncomingChatMessage).
 *
 * IMPORTANT quirk to know about, not a bug: every id here (recipientId,
 * senderId) is a STRING, even though UserSummary.id (from GET /users, see
 * user.models.ts) is a NUMBER. This isn't an inconsistency in this file —
 * it's because the two backends represent ids differently on the wire:
 * user-service's HTTP JSON serializes Java's `Long` as a plain number, but
 * the JWT "sub" claim (and everything chat-service derives from it) is
 * built as a STRING (JwtService.java does `String.valueOf(userId)`). So
 * ChatService.sendMessage() has to explicitly call `.toString()` on a
 * UserSummary.id before using it as a recipientId — see ChatService.
 */

export type ChatMessageType = 'message' | 'ack' | 'incoming_message' | 'delivered_ack';

// Client -> Server: send a chat message.
export interface ChatMessageRequest {
  type: 'message';
  messageId: string;
  recipientId: string;
  content: string;
}

// Server -> Sender: tick acknowledgment. `tick: 'double'` arrives once the
// recipient's client sends a DeliveredAck back (build-order step 9).
export interface TickAck {
  type: 'ack';
  tick: 'single' | 'double';
  messageId: string;
}

// Server -> Recipient: a live-delivered message.
// sentAt (ISO-8601 string, same shape as ConversationMessageResponse.sentAt
// below) was added alongside message timestamps in the UI — chat-service
// mints ONE Instant per message and shares it with both this live envelope
// and the persisted row, so a message's live-delivered timestamp and its
// later-read-from-history timestamp are always identical.
export interface IncomingChatMessage {
  type: 'incoming_message';
  messageId: string;
  senderId: string;
  content: string;
  sentAt: string;
}

// Client -> Server (build-order step 9): sent automatically by ChatService
// the instant it receives an IncomingChatMessage — no user action involved.
// senderId is just echoed back from that IncomingChatMessage; see
// chat-service's DeliveredAck.java for the full reasoning on why the
// client supplies it rather than the server tracking it itself.
export interface DeliveredAck {
  type: 'delivered_ack';
  messageId: string;
  senderId: string;
}

/**
 * Server -> client, a bot reply being written (CLAUDE.md 3.10).
 *
 * Three types, all carrying the messageId the finished message will have.
 * That id is the thread tying them together: `bot_stream_start` opens an
 * empty bubble, each `bot_stream_delta` grows it, and `bot_status` shows a
 * transient "looking something up" note in its place.
 *
 * None of these is the real message. The ordinary `incoming_message` still
 * arrives with the same messageId and REPLACES the bubble's text, which is
 * what keeps this safe: a dropped frame, or ignoring these types entirely,
 * still ends at the correct final message.
 *
 * Only the tool-calling bot sends these. The prompt-stuffing bot replies in
 * one piece, which is itself part of the comparison.
 */
export interface BotStreamEvent {
  type: 'bot_stream_start' | 'bot_stream_delta' | 'bot_status';
  messageId: string;
  senderId: string;
  /** The chunk to append, or the status to show. Absent on `bot_stream_start`. */
  text: string | null;
}

/** A discriminated union of everything the server can send after auth succeeds. */
export type ServerChatEvent = TickAck | IncomingChatMessage | BotStreamEvent;

/**
 * One message from GET /conversations/{otherUserId}/messages
 * (ConversationMessageResponse.java). A REST response, not a WebSocket
 * envelope — no `type` field, unlike everything above — which is why this
 * isn't folded into ServerChatEvent.
 *
 * `sentAt` arrives as an ISO-8601 string (Jackson's default Instant
 * serialization) — kept as a string on the wire type itself; ChatComponent
 * parses it into a `Date` only at render time (for the WhatsApp-style
 * today-vs-older timestamp format), not stored pre-parsed here.
 */
export interface ConversationMessageResponse {
  messageId: string;
  senderId: string;
  recipientId: string;
  content: string;
  sentAt: string;
  delivered: boolean;
}

/**
 * The response body for GET /conversations/{otherUserId}/messages.
 *
 * `hasMore` (added alongside cursor pagination): true when a full page (50)
 * came back, meaning an older page might still exist — ChatComponent's
 * scroll-to-top handler uses this to know when to stop trying. See
 * ConversationHistoryResponse.java (chat-service) for the accepted
 * imprecision this carries.
 */
export interface ConversationHistoryResponse {
  messages: ConversationMessageResponse[];
  message: string;
  hasMore: boolean;
}
