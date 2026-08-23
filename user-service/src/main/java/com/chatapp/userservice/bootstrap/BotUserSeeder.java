package com.chatapp.userservice.bootstrap;

import com.chatapp.userservice.entity.User;
import com.chatapp.userservice.entity.UserType;
import com.chatapp.userservice.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Creates the DoctorAssistant bot's {@code users} row on startup, if it isn't
 * there already (CLAUDE.md 3.9, build-order step 18).
 *
 * <p><b>Why user-service does this and not chat-service</b>, even though the bot
 * is entirely chat-service's feature: user-service owns the {@code users} table
 * and is its only writer, a boundary the chatappdb consolidation (CLAUDE.md 3.5)
 * gave up at the database level but not at the code level. It is also the only
 * service with a {@link PasswordEncoder} wired up, which the NOT NULL password
 * column below forces someone to have. Seeding from chat-service would mean
 * adding spring-security-crypto there to bcrypt exactly one value that is
 * designed never to be checked.
 *
 * <p>docker-compose.yml orders chat-service after this service's healthcheck
 * passes, so by the time chat-service looks the row is already there.
 *
 * <p>Runs on every boot, not just the first: the existence check makes a repeat
 * run a single cheap SELECT, which is a much better property than a one-shot
 * migration that silently does nothing after somebody deletes the row by hand
 * while debugging.
 */
@Component
public class BotUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BotUserSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String botUsername;
    private final String botDisplayName;

    public BotUserSeeder(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${bot.username}") String botUsername,
            @Value("${bot.display-name}") String botDisplayName) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.botUsername = botUsername;
        this.botDisplayName = botDisplayName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByUsername(botUsername)) {
            log.info("Bot user '{}' already present, nothing to seed", botUsername);
            return;
        }

        User bot = new User(
                botUsername,
                unusablePassword(),
                botDisplayName,
                // NULL, deliberately: "DoctorAssistant" is a whole name, not a
                // first name awaiting a surname. The column is already nullable
                // (lastName is optional at registration), and the frontend
                // already renders a null last name, so nothing needs a
                // placeholder here.
                null,
                UserType.BOT);

        User saved = userRepository.save(bot);
        log.info("Seeded bot user '{}' with id {}", saved.getUsername(), saved.getId());
    }

    /**
     * A bcrypt hash of 256 bits of {@link SecureRandom} noise that is generated
     * here, never stored anywhere else, and immediately discarded.
     *
     * <p>The bot has no browser and never calls {@code POST /login}, so nothing
     * will ever verify this hash — but {@code password} is NOT NULL, so the
     * column needs something. The two tempting shortcuts are both worse than
     * they look: an empty string or a fixed literal like "bot" is a password
     * somebody could actually present at /login, and it would authenticate. A
     * random value nobody ever learns cannot be presented at all, so the login
     * endpoint needs no special case for bot accounts to stay closed to them.
     *
     * <p>Regenerated on the (rare) reseed rather than held as a constant, for
     * the same reason: there is no value in it being reproducible, and a
     * constant is exactly what would end up copied into a test fixture and then
     * into someone's notes.
     */
    private String unusablePassword() {
        byte[] noise = new byte[32];
        new SecureRandom().nextBytes(noise);
        return passwordEncoder.encode(Base64.getEncoder().encodeToString(noise));
    }
}
