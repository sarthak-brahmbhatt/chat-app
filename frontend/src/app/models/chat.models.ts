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

export type ChatMessageType = 'message' | 'ack' | 'incoming_message';

// Client -> Server: send a chat message.
export interface ChatMessageRequest {
  type: 'message';
  messageId: string;
  recipientId: string;
  content: string;
}

// Server -> Sender: tick acknowledgment. `tick` is 'single' only for now —
// 'double' arrives in build-order step 9 and isn't handled by ChatComponent
// yet (explicitly out of scope for this pass).
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

/** A discriminated union of everything the server can send after auth succeeds. */
export type ServerChatEvent = TickAck | IncomingChatMessage;
