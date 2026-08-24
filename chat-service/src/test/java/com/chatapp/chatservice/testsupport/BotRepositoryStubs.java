package com.chatapp.chatservice.testsupport;

import com.chatapp.chatservice.bot.repository.AppointmentRepository;
import com.chatapp.chatservice.bot.repository.BotConversationStateRepository;
import com.chatapp.chatservice.bot.repository.BotPromptLogRepository;
import com.chatapp.chatservice.bot.repository.BotTokenUsageRepository;
import com.chatapp.chatservice.bot.repository.DoctorAvailabilityRepository;
import com.chatapp.chatservice.bot.repository.DoctorRepository;
import com.chatapp.chatservice.repository.AppUserRepository;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Mock beans for every repository the bot package needs, for the two full-context
 * tests that deliberately switch JPA off.
 *
 * <p>Those tests (ChatWebSocketIntegrationTest, KafkaEndToEndTest) exclude
 * DataSource/JPA auto-configuration because they are about sockets and Kafka and
 * have no business requiring a running MySQL — see their own class comments. The
 * cost is that Spring Data creates no repository beans at all, so every bot
 * component added in build-order step 18 becomes unsatisfiable and the context
 * fails to start. None of them are exercised by either test; they just have to
 * exist.
 *
 * <p>Shared rather than six {@code @MockBean} fields duplicated across both
 * classes: the list is pure context-wiring noise in both, and having one place to
 * add to means the next bot repository breaks neither test.
 */
@TestConfiguration
public class BotRepositoryStubs {

    @Bean
    public AppUserRepository appUserRepository() {
        return Mockito.mock(AppUserRepository.class);
    }

    @Bean
    public DoctorRepository doctorRepository() {
        return Mockito.mock(DoctorRepository.class);
    }

    @Bean
    public DoctorAvailabilityRepository doctorAvailabilityRepository() {
        return Mockito.mock(DoctorAvailabilityRepository.class);
    }

    @Bean
    public AppointmentRepository appointmentRepository() {
        return Mockito.mock(AppointmentRepository.class);
    }

    @Bean
    public BotConversationStateRepository botConversationStateRepository() {
        return Mockito.mock(BotConversationStateRepository.class);
    }

    @Bean
    public BotTokenUsageRepository botTokenUsageRepository() {
        return Mockito.mock(BotTokenUsageRepository.class);
    }

    @Bean
    public BotPromptLogRepository botPromptLogRepository() {
        return Mockito.mock(BotPromptLogRepository.class);
    }
}
