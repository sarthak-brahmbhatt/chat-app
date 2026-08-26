package com.chatapp.chatservice.bot.toolcalling;

import com.chatapp.chatservice.bot.promptstuffing.BotBrainException;
import com.openai.client.OpenAIClient;
import com.openai.core.ObjectMappers;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.services.blocking.ResponseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tool-calling loop itself (CLAUDE.md 3.10).
 *
 * <p>This is the one place the SDK genuinely is mocked, which the rest of the
 * codebase deliberately avoids. The justification is that the loop is Version
 * 2's substance — how many rounds it runs, whether it sums cost across all of
 * them, whether it stops — and none of that is observable from outside without
 * either a billable network call or this.
 *
 * <p>Responses are built by deserialising realistic API JSON with the SDK's own
 * mapper rather than by hand-assembling builders. That keeps the fixtures
 * readable AND means they are parsed by exactly the code that parses the real
 * thing — a shape the SDK would reject in production is rejected here too.
 */
@ExtendWith(MockitoExtension.class)
class OpenAiToolCallingBrainTest {

    @Mock private OpenAIClient client;
    @Mock private ResponseService responseService;

    private OpenAiToolCallingBrain brain;
    private ClinicToolExecutor executor;
    private final List<String> executed = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(client.responses()).thenReturn(responseService);
        brain = new OpenAiToolCallingBrain(client, "gpt-4o-mini");

        // A stub executor that records what it was asked to run. The real one is
        // covered against a real database in ClinicToolExecutorTest; here the
        // only question is whether the loop calls it correctly.
        executor = new ClinicToolExecutor(null, null, null, null, null, null, null, 7, 42L) {
            @Override
            public String execute(String toolName, String argsJson) {
                executed.add(toolName);
                return "{\"ok\":true}";
            }
        };
    }

    @Test
    void aReplyWithNoToolCalls_finishesInOneRound() {
        stubResponses(messageResponse("resp_1", "Hello and welcome.", 100, 20));

        ToolTurn turn = brain.respond("rules", "hi", null, executor);

        assertThat(turn.replyToUser()).isEqualTo("Hello and welcome.");
        assertThat(turn.rounds()).isEqualTo(1);
        assertThat(turn.responseId()).isEqualTo("resp_1");
        assertThat(turn.invocations()).isEmpty();
        assertThat(executed).isEmpty();
    }

    @Test
    void toolCallsAreExecutedAndTheLoopContinuesUntilTheModelStops() {
        stubResponses(
                toolCallResponse("resp_1", "call_a", "list_specialties", "{}", 100, 10),
                toolCallResponse("resp_2", "call_b", "find_doctors", "{\"specialty\":\"Orthopedic\"}", 200, 15),
                messageResponse("resp_3", "We have Dr. Mehta.", 300, 25));

        ToolTurn turn = brain.respond("rules", "who do you have?", null, executor);

        assertThat(executed).containsExactly("list_specialties", "find_doctors");
        assertThat(turn.rounds()).isEqualTo(3);
        assertThat(turn.replyToUser()).isEqualTo("We have Dr. Mehta.");
        // The id of the LAST response — chaining from an earlier one would drop
        // the tool results from the conversation's memory.
        assertThat(turn.responseId()).isEqualTo("resp_3");
        assertThat(turn.invocations()).extracting(ToolInvocation::name)
                .containsExactly("list_specialties", "find_doctors");
    }

    @Test
    void tokensAreSummedAcrossEveryRound_notJustTheLast() {
        // Recording only the final call would flatter Version 2 by counting a
        // fraction of what the turn actually cost, and the whole comparison
        // against Version 1 runs off these numbers.
        stubResponses(
                toolCallResponse("resp_1", "call_a", "list_specialties", "{}", 100, 10),
                toolCallResponse("resp_2", "call_b", "find_doctors", "{}", 200, 15),
                messageResponse("resp_3", "done", 300, 25));

        ToolTurn turn = brain.respond("rules", "hi", null, executor);

        assertThat(turn.usage().inputTokens()).isEqualTo(600);    // 100+200+300
        assertThat(turn.usage().outputTokens()).isEqualTo(50);    // 10+15+25
        assertThat(turn.usage().totalTokens()).isEqualTo(650);
    }

    @Test
    void everyRoundResendsTheRulesAndChainsFromThePreviousResponse() {
        stubResponses(
                toolCallResponse("resp_1", "call_a", "list_specialties", "{}", 10, 1),
                messageResponse("resp_2", "done", 10, 1));

        brain.respond("THE RULES", "hi", null, executor);

        ArgumentCaptor<ResponseCreateParams> sent = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(responseService, times(2)).create(sent.capture());

        // Found live: `instructions` is not carried forward by
        // previous_response_id, so a follow-up round without it leaves the model
        // writing the reply with no behavioural prompt at all. It showed as a
        // skipped greeting and markdown the prompt forbids.
        assertThat(sent.getAllValues()).allSatisfy(p ->
                assertThat(p._body().toString()).contains("THE RULES"));

        // And the second round must chain onto the response that asked for the call.
        assertThat(sent.getAllValues().get(1)._body().toString()).contains("resp_1");
    }

    @Test
    void aRunawayLoopIsStoppedRatherThanBillingForever() {
        // Nothing forces a model to converge, and every round is a billable call
        // on a thread a patient is waiting on.
        Response[] endless = new Response[8];
        for (int i = 0; i < endless.length; i++) {
            endless[i] = toolCallResponse("resp_" + i, "call_" + i, "list_specialties", "{}", 10, 1);
        }
        stubResponses(endless);

        assertThatThrownBy(() -> brain.respond("rules", "hi", null, executor))
                .isInstanceOf(BotBrainException.class)
                .hasMessageContaining("did not settle");

        // Capped, not unbounded — 5 rounds means 5 calls, not 8.
        verify(responseService, times(5)).create(any(ResponseCreateParams.class));
    }

    @Test
    void severalToolCallsInOneRoundAreAllExecutedBeforeReplying() {
        stubResponses(
                twoToolCallResponse("resp_1", "list_specialties", "find_doctors", 50, 5),
                messageResponse("resp_2", "done", 50, 5));

        ToolTurn turn = brain.respond("rules", "hi", null, executor);

        assertThat(executed).containsExactly("list_specialties", "find_doctors");
        assertThat(turn.rounds()).isEqualTo(2);
    }

    @Test
    void aResponseWithNeitherTextNorToolCalls_isAFailureNotAnEmptyReply() {
        stubResponses(emptyResponse("resp_1"));

        assertThatThrownBy(() -> brain.respond("rules", "hi", null, executor))
                .isInstanceOf(BotBrainException.class)
                .hasMessageContaining("no text and no tool calls");
    }

    // ---- fixtures -------------------------------------------------------

    private void stubResponses(Response... responses) {
        Deque<Response> queue = new ArrayDeque<>(List.of(responses));
        when(responseService.create(any(ResponseCreateParams.class)))
                .thenAnswer(invocation -> queue.isEmpty() ? responses[responses.length - 1] : queue.poll());
    }

    private static Response messageResponse(String id, String text, int in, int out) {
        return parse(id, """
                {"type":"message","id":"msg_1","role":"assistant","status":"completed",
                 "content":[{"type":"output_text","text":"%s","annotations":[]}]}
                """.formatted(text), in, out);
    }

    private static Response toolCallResponse(String id, String callId, String name, String args, int in, int out) {
        return parse(id, """
                {"type":"function_call","id":"fc_1","call_id":"%s","name":"%s",
                 "arguments":%s,"status":"completed"}
                """.formatted(callId, name,
                ObjectMappers.jsonMapper().valueToTree(args).toString()), in, out);
    }

    private static Response twoToolCallResponse(String id, String nameA, String nameB, int in, int out) {
        return parse(id, """
                {"type":"function_call","id":"fc_1","call_id":"call_a","name":"%s","arguments":"{}","status":"completed"},
                {"type":"function_call","id":"fc_2","call_id":"call_b","name":"%s","arguments":"{}","status":"completed"}
                """.formatted(nameA, nameB), in, out);
    }

    private static Response emptyResponse(String id) {
        return parse(id, "", 1, 1);
    }

    /** Built from real API JSON, parsed by the SDK's own mapper. */
    private static Response parse(String id, String outputItems, int in, int out) {
        String json = """
                {"id":"%s","object":"response","created_at":1756000000,"model":"gpt-4o-mini",
                 "status":"completed","parallel_tool_calls":true,"tool_choice":"auto","tools":[],
                 "output":[%s],
                 "usage":{"input_tokens":%d,"output_tokens":%d,"total_tokens":%d,
                          "input_tokens_details":{"cached_tokens":0},
                          "output_tokens_details":{"reasoning_tokens":0}}}
                """.formatted(id, outputItems, in, out, in + out);
        try {
            return ObjectMappers.jsonMapper().readValue(json, Response.class);
        } catch (Exception e) {
            throw new IllegalStateException("Bad fixture JSON: " + json, e);
        }
    }
}
