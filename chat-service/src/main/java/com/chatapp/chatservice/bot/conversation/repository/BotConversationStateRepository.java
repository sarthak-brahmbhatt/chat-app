package com.chatapp.chatservice.bot.conversation.repository;

import com.chatapp.chatservice.bot.conversation.entity.BotConversationState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BotConversationStateRepository extends JpaRepository<BotConversationState, Long> {

    Optional<BotConversationState> findByConversationKey(String conversationKey);
}
