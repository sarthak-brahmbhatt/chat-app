package com.chatapp.chatservice.bot.conversation.repository;

import com.chatapp.chatservice.bot.conversation.entity.BotTokenUsage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotTokenUsageRepository extends JpaRepository<BotTokenUsage, Long> {

    /**
     * How many calls this conversation has already made, so the next row can be
     * numbered. Counted rather than kept as a counter on
     * {@code bot_conversation_state}: this table is the record of what happened,
     * so deriving the turn number from it cannot drift away from the rows it
     * numbers, whereas a separate counter can.
     */
    long countByConversationKey(String conversationKey);
}
