package com.chatapp.chatservice.bot.conversation.repository;

import com.chatapp.chatservice.bot.conversation.entity.BotRoundLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** @see BotRoundLog */
public interface BotRoundLogRepository extends JpaRepository<BotRoundLog, Long> {

    List<BotRoundLog> findByPromptLogIdOrderByRoundNumberAsc(Long promptLogId);

    List<BotRoundLog> findByConversationKeyAndTurnNumberOrderByRoundNumberAsc(
            String conversationKey, int turnNumber);
}
