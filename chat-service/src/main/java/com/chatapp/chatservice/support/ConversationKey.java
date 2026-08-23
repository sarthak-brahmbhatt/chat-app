package com.chatapp.chatservice.support;

/**
 * The canonical, direction-independent identifier for a conversation between two
 * users (CLAUDE.md 3.4).
 *
 * <p>Originally a private helper inside ChatMessagePublisher, where it existed for
 * exactly one job: Kafka partitioning. Keying by sender alone would scatter one
 * conversation's messages across partitions, and Kafka only guarantees ordering
 * WITHIN a partition — so A→B and B→A had to hash to the same key for send-order
 * to survive at all.
 *
 * <p>It moved here when the bot arrived, because a second caller now needs the
 * identical value for an unrelated reason: `bot_conversation_state` is keyed by
 * conversation, and that key has to mean the same thing it means on the Kafka
 * topic. Two independent implementations of "the canonical pair" that merely
 * agree today is exactly the kind of drift that surfaces later as a bot that
 * silently forgets the conversation in one direction only.
 */
public final class ConversationKey {

    private ConversationKey() {
    }

    /**
     * {@code min(a,b) + ":" + max(a,b)} by lexicographic order — so the same two
     * participants always produce one key no matter who is sending.
     *
     * <p>Lexicographic, not numeric, even though these ids are numeric strings.
     * That makes "10:9" the key rather than "9:10", which looks wrong but is
     * completely harmless: nothing ever parses this value back apart, and the only
     * property that matters is that it is STABLE for a given unordered pair.
     * Switching to numeric ordering now would change every existing key and orphan
     * the bot state rows already written under the old ones.
     */
    public static String of(String userIdA, String userIdB) {
        return userIdA.compareTo(userIdB) < 0
                ? userIdA + ":" + userIdB
                : userIdB + ":" + userIdA;
    }
}
