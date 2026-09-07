package com.chatapp.chatservice.bot.toolcalling;

import com.chatapp.chatservice.bot.promptstuffing.BotBrainException;
import com.openai.client.OpenAIClient;
import com.openai.core.ObjectMappers;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tool-calling loop itself (CLAUDE.md 3.10).
 *
 * <p>The one place the SDK genuinely is mocked, which the rest of the codebase
 * avoids. The loop is Version 2's substance — how many rounds it runs, whether
 * it sums cost across all of them, whether it stops, whether text reaches the
 * user as it is written — and none of that is observable without either a
 * billable network call or this.
 *
 * <p>Fixtures are real API JSON parsed by the SDK's own mapper, including the
 * event stream: each round emits {@code response.output_text.delta} events for
 * whatever text the model wrote, then a terminal {@code response.completed}
 * carrying the whole response. So the loop is exercised in exactly the shape it
 * runs in production, and a payload the SDK would reject there is rejected here.
 */
@ExtendWith(MockitoExtension.class)
class OpenAiToolCallingBrainTest {

    @Mock private OpenAIClient client;
    @Mock private ResponseService responseService;

    private OpenAiToolCallingBrain brain;
    private ClinicToolExecutor executor;

    private final List<String> executed = new ArrayList<>();
    private final List<String> deltas = new ArrayList<>();
    private final List<String> statuses = new ArrayList<>();

    private final BotStreamListener listener = new BotStreamListener() {
        @Override
        public void onStatus(String status) {
            statuses.add(status);
        }

        @Override
        public void onTextDelta(String delta) {
            deltas.add(delta);
        }
    };

    @BeforeEach
    void setUp() {
        when(client.responses()).thenReturn(responseService);
        brain = new OpenAiToolCallingBrain(client, "gpt-4o-mini");

        // Records what it was asked to run. The real executor is covered against
        // a real database in ClinicToolExecutorTest; the only question here is
        // whether the loop drives it correctly.
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
        stubRounds(message("resp_1", "Hello and welcome.", 100, 20));

        ToolTurn turn = brain.respond("rules", "hi", null, executor, listener);

        assertThat(turn.replyToUser()).isEqualTo("Hello and welcome.");
        assertThat(turn.rounds()).isEqualTo(1);
        assertThat(turn.responseId()).isEqualTo("resp_1");
        assertThat(turn.invocations()).isEmpty();
        assertThat(executed).isEmpty();
    }

    @Test
    void textIsRelayedAsItArrives_andTheChunksMakeUpTheFinalReply() {
        // The reason streaming exists: a tool-calling turn takes several seconds
        // and the user should watch it being written, not stare at a tick.
        stubRounds(message("resp_1", "Hello and welcome to Super Clinic.", 10, 5));

        ToolTurn turn = brain.respond("rules", "hi", null, executor, listener);

        assertThat(deltas).hasSizeGreaterThan(1);
        assertThat(String.join("", deltas)).isEqualTo(turn.replyToUser());
    }

    @Test
    void aStatusIsAnnouncedBeforeEachLookup() {
        // The model writes no text while calling tools, which is most of the
        // wait. Without this the window would sit empty for the slowest part.
        stubRounds(
                toolCall("resp_1", "call_a", "get_available_slots", "{}", 10, 1),
                message("resp_2", "Here you go.", 10, 1));

        brain.respond("rules", "hi", null, executor, listener);

        assertThat(statuses).containsExactly("Checking availability…");
    }

    @Test
    void statusesNeverLeakToolNamesToThePatient() {
        stubRounds(
                toolCall("resp_1", "call_a", "book_appointment", "{}", 10, 1),
                message("resp_2", "Done.", 10, 1));

        brain.respond("rules", "hi", null, executor, listener);

        // The prompt works hard not to reveal that the patient is talking to a
        // program; a status frame saying "book_appointment" would undo that.
        assertThat(statuses).singleElement().satisfies(s -> {
            assertThat(s).doesNotContain("_");
            assertThat(s).isEqualTo("Booking that for you…");
        });
    }

    @Test
    void toolCallsAreExecutedAndTheLoopContinuesUntilTheModelStops() {
        stubRounds(
                toolCall("resp_1", "call_a", "list_specialties", "{}", 100, 10),
                toolCall("resp_2", "call_b", "find_doctors", "{}", 200, 15),
                message("resp_3", "We have Dr. Mehta.", 300, 25));

        ToolTurn turn = brain.respond("rules", "who do you have?", null, executor, listener);

        assertThat(executed).containsExactly("list_specialties", "find_doctors");
        assertThat(turn.rounds()).isEqualTo(3);
        assertThat(turn.replyToUser()).isEqualTo("We have Dr. Mehta.");
        // The LAST response's id — chaining from an earlier one would drop the
        // tool results from the conversation's memory.
        assertThat(turn.responseId()).isEqualTo("resp_3");
    }

    @Test
    void tokensAreSummedAcrossEveryRound_notJustTheLast() {
        // Recording only the final call would flatter Version 2 by counting a
        // fraction of what the turn cost, and the comparison with Version 1 runs
        // entirely off these numbers.
        stubRounds(
                toolCall("resp_1", "call_a", "list_specialties", "{}", 100, 10),
                toolCall("resp_2", "call_b", "find_doctors", "{}", 200, 15),
                message("resp_3", "done", 300, 25));

        ToolTurn turn = brain.respond("rules", "hi", null, executor, listener);

        assertThat(turn.usage().inputTokens()).isEqualTo(600);
        assertThat(turn.usage().outputTokens()).isEqualTo(50);
        assertThat(turn.usage().totalTokens()).isEqualTo(650);
    }

    @Test
    void everyRoundResendsTheRulesAndChainsFromThePreviousResponse() {
        stubRounds(
                toolCall("resp_1", "call_a", "list_specialties", "{}", 10, 1),
                message("resp_2", "done", 10, 1));

        brain.respond("THE RULES", "hi", null, executor, listener);

        ArgumentCaptor<ResponseCreateParams> sent = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(responseService, times(2)).createStreaming(sent.capture());

        // Found live: `instructions` is not carried forward by
        // previous_response_id, so a follow-up round without it leaves the model
        // writing the reply with no behavioural prompt at all — a skipped
        // greeting and markdown the prompt forbids.
        assertThat(sent.getAllValues()).allSatisfy(p ->
                assertThat(p._body().toString()).contains("THE RULES"));

        assertThat(sent.getAllValues().get(1)._body().toString()).contains("resp_1");
    }

    @Test
    void aRunawayLoopIsStoppedRatherThanBillingForever() {
        String[] endless = new String[8];
        for (int i = 0; i < endless.length; i++) {
            endless[i] = toolCall("resp_" + i, "call_" + i, "list_specialties", "{}", 10, 1);
        }
        stubRounds(endless);

        assertThatThrownBy(() -> brain.respond("rules", "hi", null, executor, listener))
                .isInstanceOf(BotBrainException.class)
                .hasMessageContaining("did not settle");

        verify(responseService, times(5)).createStreaming(any(ResponseCreateParams.class));
    }

    @Test
    void severalToolCallsInOneRoundAreAllExecutedBeforeReplying() {
        stubRounds(twoToolCalls("resp_1", "list_specialties", "find_doctors", 50, 5),
                message("resp_2", "done", 50, 5));

        ToolTurn turn = brain.respond("rules", "hi", null, executor, listener);

        assertThat(executed).containsExactly("list_specialties", "find_doctors");
        assertThat(turn.rounds()).isEqualTo(2);
    }

    @Test
    void aResponseWithNeitherTextNorToolCalls_isAFailureNotAnEmptyReply() {
        stubRounds(empty("resp_1"));

        assertThatThrownBy(() -> brain.respond("rules", "hi", null, executor, listener))
                .isInstanceOf(BotBrainException.class)
                .hasMessageContaining("no text and no tool calls");
    }

    @Test
    void aStreamThatEndsWithoutCompleting_isAFailure() {
        // A truncated connection. There is no response to continue the loop
        // from, so failing loudly beats carrying on with nothing.
        when(responseService.createStreaming(any(ResponseCreateParams.class)))
                .thenAnswer(invocation -> streamOf(List.of()));

        assertThatThrownBy(() -> brain.respond("rules", "hi", null, executor, listener))
                .isInstanceOf(BotBrainException.class)
                .hasMessageContaining("without completing");
    }

    // ---- fixtures -------------------------------------------------------

    private void stubRounds(String... responseJson) {
        Deque<String> queue = new ArrayDeque<>(List.of(responseJson));
        when(responseService.createStreaming(any(ResponseCreateParams.class)))
                .thenAnswer(invocation -> {
                    String json = queue.isEmpty() ? responseJson[responseJson.length - 1] : queue.poll();
                    return streamOf(eventsFor(json));
                });
    }

    /** The events one round emits: text deltas, then response.completed. */
    private static List<ResponseStreamEvent> eventsFor(String responseJson) {
        List<ResponseStreamEvent> events = new ArrayList<>();
        int seq = 0;
        String text = textOf(responseJson);
        if (text != null) {
            // Real streams arrive a few characters at a time; splitting after
            // spaces is enough to prove chunks are relayed and concatenate.
            for (String chunk : text.split("(?<= )")) {
                events.add(event("""
                        {"type":"response.output_text.delta","delta":%s,"item_id":"msg_1",
                         "output_index":0,"content_index":0,"sequence_number":%d,"logprobs":[]}
                        """.formatted(quote(chunk), seq++)));
            }
        }
        events.add(event("""
                {"type":"response.completed","sequence_number":%d,"response":%s}
                """.formatted(seq, responseJson)));
        return events;
    }

    private static StreamResponse<ResponseStreamEvent> streamOf(List<ResponseStreamEvent> events) {
        return new StreamResponse<>() {
            @Override
            public java.util.stream.Stream<ResponseStreamEvent> stream() {
                return events.stream();
            }

            @Override
            public void close() {
            }
        };
    }

    private static ResponseStreamEvent event(String json) {
        try {
            return ObjectMappers.jsonMapper().readValue(json, ResponseStreamEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("Bad event fixture: " + json, e);
        }
    }

    private static String textOf(String responseJson) {
        try {
            var node = ObjectMappers.jsonMapper().readTree(responseJson).path("output").findValue("text");
            return node == null || node.isNull() ? null : node.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static String quote(String raw) {
        return ObjectMappers.jsonMapper().valueToTree(raw).toString();
    }

    private static String message(String id, String text, int in, int out) {
        return response(id, """
                {"type":"message","id":"msg_1","role":"assistant","status":"completed",
                 "content":[{"type":"output_text","text":%s,"annotations":[]}]}
                """.formatted(quote(text)), in, out);
    }

    private static String toolCall(String id, String callId, String name, String args, int in, int out) {
        return response(id, """
                {"type":"function_call","id":"fc_1","call_id":"%s","name":"%s",
                 "arguments":%s,"status":"completed"}
                """.formatted(callId, name, quote(args)), in, out);
    }

    private static String twoToolCalls(String id, String nameA, String nameB, int in, int out) {
        return response(id, """
                {"type":"function_call","id":"fc_1","call_id":"call_a","name":"%s","arguments":"{}","status":"completed"},
                {"type":"function_call","id":"fc_2","call_id":"call_b","name":"%s","arguments":"{}","status":"completed"}
                """.formatted(nameA, nameB), in, out);
    }

    private static String empty(String id) {
        return response(id, "", 1, 1);
    }

    private static String response(String id, String outputItems, int in, int out) {
        return """
                {"id":"%s","object":"response","created_at":1756000000,"model":"gpt-4o-mini",
                 "status":"completed","parallel_tool_calls":true,"tool_choice":"auto","tools":[],
                 "output":[%s],
                 "usage":{"input_tokens":%d,"output_tokens":%d,"total_tokens":%d,
                          "input_tokens_details":{"cached_tokens":0},
                          "output_tokens_details":{"reasoning_tokens":0}}}
                """.formatted(id, outputItems, in, out, in + out);
    }
}
