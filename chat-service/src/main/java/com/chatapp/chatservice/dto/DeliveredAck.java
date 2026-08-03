package com.chatapp.chatservice.dto;

/**
 * The client → server envelope acknowledging live delivery (CLAUDE.md 3.1,
 * build-order step 9): {"type":"delivered_ack","messageId":"...","senderId":"..."}
 *
 * Sent automatically by the RECIPIENT's client the instant it receives an
 * incoming_message — no user action required. This is what "delivered" means
 * for double tick (mirrors WhatsApp: the message reached the recipient's
 * device, not that they opened/read it — read receipts are explicitly out
 * of scope, CLAUDE.md section 5).
 *
 * senderId is supplied by the CLIENT (echoed back from the incoming_message
 * it just received), not looked up server-side from any messageId ->
 * senderId tracking chat-service would otherwise have to maintain. That's a
 * deliberate choice, not an oversight: server-side tracking would mean
 * real state that grows per undelivered/unacked message and needs its own
 * cleanup/expiry, for a value the client already has for free. Accepted
 * tradeoff: this trusts the client not to lie about senderId — a forged
 * value can only cause a spurious double-tick sent to some other connected
 * user for a messageId their UI doesn't recognize (a harmless no-op on the
 * receiving end), consistent with this project's existing bearer-token
 * trust model for an internal-only tool (CLAUDE.md 3.3). Revisit if this
 * protocol is ever exposed to less-trusted clients.
 */
public record DeliveredAck(String type, String messageId, String senderId) {
}
