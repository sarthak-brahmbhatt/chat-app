package com.chatapp.chatservice.bot.routing;

import com.chatapp.chatservice.entity.UserType;
import com.chatapp.chatservice.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Map<UserType, String> usernamesByKind;

    // Resolved bot user id -> which kind of bot it is. Populated lazily and
    // never invalidated: a bot's id and type do not change at runtime.
    //
    // ConcurrentHashMap because WebSocket messages arrive on many container
    // threads at once. Two threads racing simply do the same lookup and store
    // the same value.
    private final Map<String, UserType> kindByUserId = new ConcurrentHashMap<>();

    public BotDirectory(
            AppUserRepository appUserRepository,
            @Value("${bot.username}") String botUsername,
            @Value("${bot.tool-username}") String toolBotUsername) {
        this.appUserRepository = appUserRepository;
        this.usernamesByKind = Map.of(
                UserType.BOT, botUsername,
                UserType.BOT_TOOL, toolBotUsername);
    }

    /**
     * Which kind of bot this recipient is, or empty for a human.
     *
     * <p>The single question the routing branch asks, and the reason this
     * returns a KIND rather than a boolean: with two bots answering at once,
     * "is this a bot?" is no longer enough to decide what to do with the
     * message.
     */
    public Optional<UserType> botKindOf(String recipientId) {
        if (recipientId == null) {
            return Optional.empty();
        }
        UserType cached = kindByUserId.get(recipientId);
        if (cached != null) {
            return Optional.of(cached);
        }
        // Not a known bot id yet. Resolve every configured bot once, then
        // re-check. Only SUCCESSFUL resolutions are cached, so a bot row that
        // does not exist yet is retried on the next message rather than being
        // remembered as absent for the life of the process — which is what
        // makes a native run (no Compose ordering) still work once the row
        // appears.
        resolveAll();
        return Optional.ofNullable(kindByUserId.get(recipientId));
    }

    /** Whether this recipient is any bot at all. */
    public boolean isBot(String recipientId) {
        return botKindOf(recipientId).isPresent();
    }

    /** The user id of one bot, if it has been seeded. */
    @Transactional(readOnly = true)
    public Optional<String> botUserId(UserType kind) {
        resolveAll();
        return kindByUserId.entrySet().stream()
                .filter(e -> e.getValue() == kind)
                .map(Map.Entry::getKey)
                .findFirst();
    }

    @Transactional(readOnly = true)
    protected void resolveAll() {
        usernamesByKind.forEach((expectedKind, username) -> {
            if (kindByUserId.containsValue(expectedKind)) {
                return;
            }
            appUserRepository.findByUsername(username).ifPresent(found -> {
                // Guards against a username being pointed at an ordinary
                // account by a misconfigured BOT_USERNAME / BOT_TOOL_USERNAME.
                // Without this check that person stops receiving their messages
                // — a bot answers on their behalf — which is a spectacular
                // failure for a one-line config typo.
                if (found.getUserType() != expectedKind) {
                    log.error("User '{}' exists but its user_type is {}, not {} — refusing to route bot "
                                    + "traffic to it. Check the bot username configuration.",
                            username, found.getUserType(), expectedKind);
                    return;
                }
                String id = String.valueOf(found.getId());
                kindByUserId.put(id, expectedKind);
                log.info("Resolved {} user '{}' to id {}", expectedKind, username, id);
            });
        });
    }
}
