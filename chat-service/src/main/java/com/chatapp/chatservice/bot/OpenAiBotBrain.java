package com.chatapp.chatservice.bot;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.StructuredResponse;
import com.openai.models.responses.StructuredResponseCreateParams;
import com.openai.models.responses.StructuredResponseOutputMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Calls OpenAI's Responses API and returns one structured decision
 * (CLAUDE.md 3.9 §6, §7).
 *
 * <p><b>Plain blocking HTTP, on the WebSocket's own inbound thread.</b> §7 rules
 * out the two things that look like they belong here and don't. A WebSocket to
 * OpenAI solves nothing — this is one request with exactly one reply, which is
 * what HTTP is. And WebFlux would be a second, different stack inside a Spring
 * MVC service to solve a concurrency problem this bot does not have: the
 * existing WebSocket can already push to the browser at any moment from ordinary
 * blocking code, which is the only capability reactive would have been reached
 * for. The cost of blocking is one thread parked for the length of the call,
 * bounded by the configured timeout.
 *
 * <p>Version 1 does not stream. The whole reply lands at once and is sent as a
 * single chat message, which is also what makes it a normal {@code messages} row
 * indistinguishable from a human's.
 */
@Component
public class OpenAiBotBrain implements BotBrain {

    private static final Logger log = LoggerFactory.getLogger(OpenAiBotBrain.class);

    private final OpenAIClient client;
    private final String model;

    public OpenAiBotBrain(
            @Value("${openai.api-key}") String apiKey,
            @Value("${openai.model}") String model,
            @Value("${openai.timeout-seconds}") long timeoutSeconds) {
        this.model = model;
        // A blank key is an expected state, not a misconfiguration to fail
        // startup over: chat-service's real job is human-to-human chat, and
        // refusing to boot without an OpenAI credential would make an optional
        // feature a hard dependency of the whole service. The client is simply
        // never built, isConfigured() reports false, and the bot apologises.
        if (apiKey == null || apiKey.isBlank()) {
            this.client = null;
            log.warn("No OpenAI API key configured — the DoctorAssistant bot will reply that it is unavailable. "
                    + "Set OPENAI_API_KEY (see .env.example) to enable it.");
        } else {
            this.client = OpenAIOkHttpClient.builder()
                    .apiKey(apiKey)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            log.info("DoctorAssistant bot configured with model {}", model);
        }
    }

    @Override
    public boolean isConfigured() {
        return client != null;
    }

    @Override
    public BotTurn respond(String systemPrompt, String userMessage, String previousResponseId) {
        if (!isConfigured()) {
            throw new BotBrainException("No OpenAI API key configured");
        }

        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(model)
                // The rules and the whole stuffed dataset go in `instructions`,
                // NOT as a conversation message — see BotPromptBuilder for why
                // that distinction is what keeps chained turns from accumulating
                // several contradictory snapshots of what is booked.
                .instructions(systemPrompt)
                .input(userMessage)
                // Load-bearing for multi-turn memory, not a default worth
                // relying on implicitly: previous_response_id can only resolve a
                // response the API actually retained. With store=false, chaining
                // fails on the SECOND turn — the first call succeeds, so the
                // breakage looks like the bot randomly forgetting rather than a
                // configuration error.
                .store(true);

        if (previousResponseId != null && !previousResponseId.isBlank()) {
            builder.previousResponseId(previousResponseId);
        }

        // .text(Class) must come last: it changes the builder's type from
        // ResponseCreateParams.Builder to StructuredResponseCreateParams.Builder<T>,
        // which is what carries the generated JSON schema through to a typed reply.
        StructuredResponseCreateParams<BotDecision> params = builder.text(BotDecision.class).build();

        StructuredResponse<BotDecision> response;
        try {
            response = client.responses().create(params);
        } catch (RuntimeException e) {
            throw new BotBrainException("OpenAI Responses API call failed: " + e.getMessage(), e);
        }

        BotDecision decision = extractDecision(response);
        TokenUsage usage = response.usage()
                .map(u -> new TokenUsage(
                        Math.toIntExact(u.inputTokens()),
                        Math.toIntExact(u.outputTokens()),
                        Math.toIntExact(u.totalTokens())))
                .orElseGet(TokenUsage::unknown);

        log.debug("Bot turn: action={} tokens in/out/total={}/{}/{}",
                decision.action(), usage.inputTokens(), usage.outputTokens(), usage.totalTokens());

        return new BotTurn(decision, response.id(), usage, model);
    }

    /**
     * Digs the parsed decision out of the response envelope.
     *
     * <p>The output is a LIST of items, of which a text message is only one kind
     * — the same envelope carries reasoning items and tool calls for models and
     * features that produce them. Version 1 uses no tools, so exactly one message
     * item is expected, but walking the list is what makes that an assumption
     * about this configuration rather than about the response format.
     *
     * <p>A refusal is checked before the empty case so the log distinguishes
     * "the model declined" from "nothing came back", which point at completely
     * different fixes: the first is a prompt problem, the second a plumbing one.
     */
    private BotDecision extractDecision(StructuredResponse<BotDecision> response) {
        Optional<String> refusal = response.output().stream()
                .flatMap(item -> item.message().stream())
                .flatMap(message -> message.content().stream())
                .flatMap(content -> content.refusal().stream())
                .map(r -> r.refusal())
                .findFirst();
        if (refusal.isPresent()) {
            throw new BotBrainException("Model refused to answer: " + refusal.get());
        }

        return response.output().stream()
                .flatMap(item -> item.message().stream())
                .flatMap((StructuredResponseOutputMessage<BotDecision> message) -> message.content().stream())
                .flatMap(content -> content.outputText().stream())
                .findFirst()
                .orElseThrow(() -> new BotBrainException("Response contained no structured output"));
    }
}
