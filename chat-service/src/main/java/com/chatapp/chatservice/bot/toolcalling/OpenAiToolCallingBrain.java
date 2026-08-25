package com.chatapp.chatservice.bot.toolcalling;

import com.chatapp.chatservice.bot.promptstuffing.BotBrainException;
import com.chatapp.chatservice.bot.promptstuffing.ExpiredConversationException;
import com.chatapp.chatservice.bot.promptstuffing.TokenUsage;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The tool-calling loop (CLAUDE.md 3.10).
 *
 * <p>Version 1 makes one API call and is done. This makes several, because the
 * model works incrementally: ask what specialties exist, then which doctors,
 * then what is free on Tuesday, then reply. Each round is a real call, and the
 * loop runs until the model stops asking.
 *
 * <pre>
 *   send prompt + tools
 *   while the response contains function calls:
 *       run each one for real
 *       send the outputs back, chained to that response
 *   the response with no function calls carries the reply
 * </pre>
 *
 * <p>Chaining is what makes this work with almost no prompt: each follow-up
 * carries only {@code previous_response_id} and the tool outputs, and the API
 * holds the rest. The clinic data never travels in the prompt at all — which is
 * the entire reason Version 2's token cost stops scaling with the size of the
 * clinic.
 */
@Component
public class OpenAiToolCallingBrain implements ToolCallingBrain {

    private static final Logger log = LoggerFactory.getLogger(OpenAiToolCallingBrain.class);

    /**
     * A hard stop on the loop.
     *
     * <p>Nothing forces a model to converge. A confused one can call the same
     * lookup forever, and every round is a billable call on a thread a patient
     * is waiting on. Five is comfortably more than the observed need — the
     * longest real conversations use two or three — while still bounding the
     * damage. Hitting it is a bug worth seeing in the log, not a normal path.
     */
    private static final int MAX_ROUNDS = 5;

    private final OpenAIClient client;
    private final String model;

    public OpenAiToolCallingBrain(
            @Value("${openai.api-key}") String apiKey,
            @Value("${openai.model}") String model,
            @Value("${openai.timeout-seconds}") long timeoutSeconds) {
        this.model = model;
        if (apiKey == null || apiKey.isBlank()) {
            this.client = null;
            log.warn("No OpenAI API key configured — the tool-calling bot will reply that it is unavailable.");
        } else {
            this.client = OpenAIOkHttpClient.builder()
                    .apiKey(apiKey)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            log.info("Tool-calling bot configured with model {}", model);
        }
    }

    @Override
    public boolean isConfigured() {
        return client != null;
    }

    @Override
    public ToolTurn respond(String systemPrompt, String userMessage, String previousResponseId,
                            ClinicToolExecutor executor) {
        if (!isConfigured()) {
            throw new BotBrainException("No OpenAI API key configured");
        }

        List<ToolInvocation> invocations = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;
        int totalTokens = 0;

        // Round one: the user's message, the small prompt, and the tools.
        ResponseCreateParams.Builder first = ResponseCreateParams.builder()
                .model(model)
                .instructions(systemPrompt)
                .input(userMessage)
                .store(true);
        if (previousResponseId != null && !previousResponseId.isBlank()) {
            first.previousResponseId(previousResponseId);
        }
        ClinicTool.allAsFunctionTools().forEach(first::addTool);

        Response response = send(first.build(), previousResponseId);
        int rounds = 1;

        while (true) {
            TokenUsage usage = usageOf(response);
            inputTokens += usage.inputTokens();
            outputTokens += usage.outputTokens();
            totalTokens += usage.totalTokens();

            List<ResponseFunctionToolCall> calls = response.output().stream()
                    .flatMap(item -> item.functionCall().stream())
                    .toList();

            if (calls.isEmpty()) {
                // No more lookups wanted — this response carries the answer.
                String text = extractText(response);
                log.debug("Tool-calling turn finished in {} round(s), {} tool call(s), {} tokens",
                        rounds, invocations.size(), totalTokens);
                return new ToolTurn(text, response.id(),
                        new TokenUsage(inputTokens, outputTokens, totalTokens),
                        model, List.copyOf(invocations), rounds);
            }

            if (rounds >= MAX_ROUNDS) {
                // Give up rather than loop. The turn still has whatever the
                // model last said, and the caller turns it into an apology.
                log.warn("Tool-calling loop hit {} rounds without settling; tools called so far: {}",
                        MAX_ROUNDS, invocations.stream().map(ToolInvocation::name).toList());
                throw new BotBrainException("Tool-calling loop did not settle within " + MAX_ROUNDS + " rounds");
            }

            // Run every requested call, then hand all the outputs back at once.
            List<ResponseInputItem> outputs = new ArrayList<>();
            for (ResponseFunctionToolCall call : calls) {
                String result = executor.execute(call.name(), call.arguments());
                invocations.add(new ToolInvocation(call.name(), call.arguments(), result));
                log.debug("Tool {} -> {}", call.name(),
                        result.length() > 200 ? result.substring(0, 200) + "…" : result);
                outputs.add(ResponseInputItem.ofFunctionCallOutput(
                        ResponseInputItem.FunctionCallOutput.builder()
                                // call_id ties the result to the request. Get
                                // this wrong and the API rejects the whole turn.
                                .callId(call.callId())
                                .output(result)
                                .build()));
            }

            ResponseCreateParams.Builder next = ResponseCreateParams.builder()
                    .model(model)
                    // Re-sent on EVERY round, not just the first. `instructions`
                    // applies to one call and is NOT carried forward by
                    // previous_response_id — the chain carries the conversation,
                    // not the rules. Omitting it here left the round that
                    // actually writes the reply with no behavioural prompt at
                    // all, which showed up live as a skipped greeting and
                    // markdown formatting the prompt forbids. The conversation
                    // itself still comes from the chain; only these rules repeat.
                    .instructions(systemPrompt)
                    // Chained to the response that ASKED for these calls, so the
                    // clinic data never has to be re-sent.
                    .previousResponseId(response.id())
                    .inputOfResponse(outputs)
                    .store(true);
            ClinicTool.allAsFunctionTools().forEach(next::addTool);

            response = send(next.build(), response.id());
            rounds++;
        }
    }

    private Response send(ResponseCreateParams params, String chainedFrom) {
        try {
            return client.responses().create(params);
        } catch (OpenAIServiceException e) {
            if (chainedFrom != null && isUnknownPreviousResponse(e)) {
                throw new ExpiredConversationException(
                        "previous_response_id " + chainedFrom + " is no longer known to OpenAI", e);
            }
            throw new BotBrainException("OpenAI Responses API call failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new BotBrainException("OpenAI Responses API call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Same recogniser as Version 1's. Note it can now fire mid-loop, not only on
     * the first call — though a mid-loop expiry would mean the chain aged out
     * between two calls seconds apart, which should never happen. The caller
     * retries the whole turn from scratch either way.
     */
    private boolean isUnknownPreviousResponse(OpenAIServiceException e) {
        if (e.param().filter("previous_response_id"::equals).isPresent()) {
            return true;
        }
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("previous response") && lower.contains("not found");
    }

    private TokenUsage usageOf(Response response) {
        return response.usage()
                .map(u -> new TokenUsage(
                        Math.toIntExact(u.inputTokens()),
                        Math.toIntExact(u.outputTokens()),
                        Math.toIntExact(u.totalTokens())))
                .orElseGet(TokenUsage::unknown);
    }

    /**
     * The assistant's text from a response with no outstanding tool calls.
     *
     * <p>No structured output here, unlike Version 1 — booking happens through a
     * tool, so there is no decision for Java to interpret and nothing to
     * constrain the reply's shape to. Plain text is the whole answer.
     */
    private String extractText(Response response) {
        String text = response.output().stream()
                .flatMap(item -> item.message().stream())
                .flatMap(message -> message.content().stream())
                .flatMap(content -> content.outputText().stream())
                .map(t -> t.text())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);

        if (text.isBlank()) {
            throw new BotBrainException("Response contained no text and no tool calls");
        }
        return text;
    }
}
