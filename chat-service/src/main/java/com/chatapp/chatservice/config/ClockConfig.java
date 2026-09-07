package com.chatapp.chatservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Makes "now" an injected dependency rather than a static call.
 *
 * <p>Introduced with the bot, which is unusually date-sensitive: it resolves
 * "tomorrow" against today, refuses to book the past, and matches a date's
 * weekday against a recurring pattern. Testing any of that against
 * {@code LocalDate.now()} means either tests that pass differently on a Monday,
 * or tests that construct dates relative to the real today and therefore assert
 * nothing about the boundaries. An injected Clock lets a test fix the date and
 * make those cases deterministic.
 *
 * <p>Deliberately NOT applied to the existing message path. {@code Instant.now()}
 * in ChatWebSocketHandler is a timestamp being recorded, not a value being
 * reasoned about, and threading a Clock through it would be churn for no test
 * that wants to exist.
 */
@Configuration
public class ClockConfig {

    /**
     * The system default zone, i.e. the container's — which for local Docker is
     * UTC unless the image says otherwise. That is the clinic's clock as far as
     * this app is concerned; per-user timezones are not a thing the design has,
     * and inventing one here would only give the appointment times two different
     * meanings.
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
