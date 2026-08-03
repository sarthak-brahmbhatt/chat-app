package com.chatapp.chatservice.repository;

import com.chatapp.chatservice.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The only place in chat-service that talks to messagedb directly — same
 * layering discipline as user-service's UserRepository.
 *
 * existsByMessageId is what makes ChatMessageConsumer's persistence
 * idempotent: a derived query Spring Data JPA generates from the method
 * name, same mechanism as UserRepository.existsByUsername.
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    boolean existsByMessageId(String messageId);
}
