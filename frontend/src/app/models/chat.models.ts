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
export interface IncomingChatMessage {
  type: 'incoming_message';
  messageId: string;
  senderId: string;
  content: string;
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

/** A discriminated union of everything the server can send after auth succeeds. */
export type ServerChatEvent = TickAck | IncomingChatMessage;

/**
 * One message from GET /conversations/{otherUserId}/messages
 * (ConversationMessageResponse.java). A REST response, not a WebSocket
 * envelope — no `type` field, unlike everything above — which is why this
 * isn't folded into ServerChatEvent.
 *
 * `sentAt` arrives as an ISO-8601 string (Jackson's default Instant
 * serialization) — kept as a string here rather than parsed into a Date,
 * since ChatComponent only ever needs to sort/display these once already
 * in the server's own oldest-to-newest order, never to do date arithmetic
 * on them.
 */
export interface ConversationMessageResponse {
  messageId: string;
  senderId: string;
  recipientId: string;
  content: string;
  sentAt: string;
  delivered: boolean;
}

/** The response body for GET /conversations/{otherUserId}/messages. */
export interface ConversationHistoryResponse {
  messages: ConversationMessageResponse[];
  message: string;
}
