package com.chatapp.chatservice.bot.conversation.repository;

import com.chatapp.chatservice.bot.conversation.entity.BotPromptLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BotPromptLogRepository extends JpaRepository<BotPromptLog, Long> {

    List<BotPromptLog> findByConversationKeyOrderByTurnNumberAsc(String conversationKey);
}
