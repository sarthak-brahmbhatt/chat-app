package com.chatapp.chatservice.service;

/**
 * One row ChatMessageService.sweepUndeliveredForRecipient just marked
 * delivered, carrying exactly what ChatWebSocketHandler needs to live-notify
 * the original sender (see notifyPendingDeliveries) - never serialized as-is
 * onto a socket (that's TickAck's job), so this lives here rather than in
 * dto/, which is reserved for actual wire-protocol shapes (CLAUDE.md 3.1).
 */
public record PendingDeliveryNotification(String messageId, String senderId) {
}
