package com.chatapp.chatservice.bot.clinical;

import com.chatapp.chatservice.bot.routing.BotReply;
import com.chatapp.chatservice.bot.toolcalling.BotStreamListener;
import com.chatapp.chatservice.service.ChatMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Runs one independent clinical extraction turn. */
@Service
public class ClinicalExtractorBotService {

    private static final Logger log = LoggerFactory.getLogger(ClinicalExtractorBotService.class);

    private final ClinicalExtractorClient client;
    private final ChatMessageService chatMessageService;

    public ClinicalExtractorBotService(ClinicalExtractorClient client, ChatMessageService chatMessageService) {
        this.client = client;
        this.chatMessageService = chatMessageService;
    }

    public BotReply handleUserMessage(
            String senderId,
            String botId,
            String userMessageId,
            String note,
            Instant sentAt,
            String replyMessageId,
            BotStreamListener listener) {
        chatMessageService.persistBotConversationMessage(
                userMessageId, senderId, botId, note, sentAt);
        try {
            return new BotReply(replyMessageId, client.extract(note, listener));
        } catch (ClinicalExtractorException error) {
            log.warn("Clinical extraction failed: {}", error.getMessage());
            return new BotReply(replyMessageId,
                    "{\n  \"error\": \"Clinical extraction failed. Review the extractor logs.\"\n}");
        }
    }
}
