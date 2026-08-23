package com.chatapp.chatservice.bot;

import com.chatapp.chatservice.entity.AppUser;
import com.chatapp.chatservice.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Answers the one question the routing branch asks: is this recipient the bot?
 * (CLAUDE.md 3.9 §5.2)
 *
 * <p>Asked on EVERY outgoing chat message, so it must not be a database round
 * trip per message — that would put a query on the hot path of ordinary
 * human-to-human chat, which is the overwhelming majority of traffic and got
 * along fine without one.
 *
 * <p>Resolved lazily and then memoised. Not resolved at startup, even though
 * docker-compose orders this service after user-service has seeded the row: a
 * native/IntelliJ run has no such ordering, and a startup lookup that ran too
 * early would cache "there is no bot" permanently and the feature would be
 * silently dead for the life of the process. Lazily means the first message
 * after the row exists picks it up.
 *
 * <p>Only the SUCCESSFUL lookup is cached. A miss stays uncached and is retried
 * on the next message, which is the behaviour that makes the above work — at the
 * cost of one indexed lookup per message while no bot row exists.
 */
@Component
public class BotDirectory {

    private static final Logger log = LoggerFactory.getLogger(BotDirectory.class);

    private final AppUserRepository appUserRepository;
    private final String botUsername;

    // The bot's users.id as a STRING, because that is what it gets compared
    // against: every id on the WebSocket wire is a string (the JWT "sub" claim,
    // built by user-service with String.valueOf). Converting once here beats
    // parsing a Long out of every recipientId just to compare it.
    //
    // AtomicReference rather than a plain field: WebSocket messages arrive on
    // many container threads at once. A torn read is not really possible for a
    // reference on any JVM, but "we thought about the concurrency" is worth
    // stating in the type, and the cost is nil. Two threads racing to resolve
    // simply both do the same lookup and store the same value.
    private final AtomicReference<String> cachedBotUserId = new AtomicReference<>();

    public BotDirectory(AppUserRepository appUserRepository, @Value("${bot.username}") String botUsername) {
        this.appUserRepository = appUserRepository;
        this.botUsername = botUsername;
    }

    /**
     * Whether messages addressed to {@code recipientId} should go to the bot
     * instead of through the ConnectionRegistry.
     */
    public boolean isBot(String recipientId) {
        return recipientId != null && recipientId.equals(botUserId().orElse(null));
    }

    /** The bot's user id, or empty if no BOT row exists yet. */
    @Transactional(readOnly = true)
    public Optional<String> botUserId() {
        String cached = cachedBotUserId.get();
        if (cached != null) {
            return Optional.of(cached);
        }

        Optional<AppUser> bot = appUserRepository.findByUsername(botUsername);
        if (bot.isEmpty()) {
            return Optional.empty();
        }

        AppUser found = bot.get();
        // Guards against the username being pointed at an ordinary account by a
        // misconfigured BOT_USERNAME. Without this check that account's owner
        // would stop receiving their messages — the bot would answer on their
        // behalf — which is a spectacular failure for a one-line config typo.
        if (!found.isBot()) {
            log.error("User '{}' exists but its user_type is {}, not BOT — refusing to route bot traffic to it. "
                    + "Check the BOT_USERNAME configuration.", botUsername, found.getUserType());
            return Optional.empty();
        }

        String id = String.valueOf(found.getId());
        cachedBotUserId.set(id);
        log.info("Resolved bot user '{}' to id {}", botUsername, id);
        return Optional.of(id);
    }
}
