# OpenAI API playground — parameter guide

Companion to [`postman/openai-playground.postman_collection.json`](../postman/openai-playground.postman_collection.json).
It covers the two APIs this repo's bots actually use: **Chat Completions**
(CLAUDE.md 3.9's shape) and the **Responses API** (what `OpenAiBotBrain` and
`OpenAiToolCallingBrain` call).

Every request in the collection carries its own notes in Postman's
documentation pane — this file is the reference you read alongside it.

---

## 1. Setup

1. Import both files in Postman (**Import** → drag the `postman/` folder).
2. Collection → **Variables** tab → put your real key in the **Current value**
   column for `api_key`. Postman never exports current values, so the key stays
   on your machine even if you share the collection.
3. Send **3.1 List models**. A 200 proves key + auth header + base URL. Every
   later 401 is then about that specific request, not your setup.

Auth is set once at the collection level (`Authorization: Bearer {{api_key}}`),
so no individual request repeats it.

| Variable | Default | Notes |
|---|---|---|
| `base_url` | `https://api.openai.com/v1` | Swap for a proxy/gateway if needed |
| `api_key` | `sk-REPLACE-WITH-YOUR-OPENAI-KEY` | Placeholder — replace in Current value |
| `model` | `gpt-4o-mini` | Same default as `OPENAI_MODEL` in `chat-service/src/main/resources/application.yml` |
| `reasoning_model` | `o4-mini` | Only request 2.8 uses it |
| `previous_response_id`, `response_id`, `tool_call_id` | *(empty)* | Auto-captured by test scripts — don't set by hand |

Open the Postman **Console** (`⌥⌘C`). Every request logs the reply text, the
token usage and any captured id, so you can watch cost move as you turn knobs.

---

## 2. The difference in one paragraph

**Chat Completions is stateless.** Memory exists only because you resend the
entire `messages` array every turn, so input tokens grow linearly with
conversation length and you pay for the whole history on every call.

**Responses is chained.** You send `previous_response_id` and OpenAI supplies
the history from its side. That single id is what
`bot_conversation_state.last_response_id` stores — which is why chat-service
holds a pointer per conversation rather than a transcript, and why restarting
the service loses no context.

The catch, and it is the one that bit this project: **`instructions` is not
part of the chain.** It applies to one call. `OpenAiToolCallingBrain` re-sends
it on every tool round for exactly this reason (CLAUDE.md 3.10).

---

## 3. Shape differences worth memorising

These trip people up when porting a request between the two APIs.

| | Chat Completions | Responses |
|---|---|---|
| System prompt | `messages[{role:"system"}]` | `instructions` (per-call, not chained) |
| User input | `messages[{role:"user"}]` | `input` — a string **or** an item array |
| Reply location | `choices[0].message.content` | `output[].content[].text` where `type == "output_text"` |
| Reply cap | `max_completion_tokens` | `max_output_tokens` |
| Token fields | `usage.prompt_tokens` / `completion_tokens` | `usage.input_tokens` / `output_tokens` |
| Tool definition | nested: `{type, function:{name, description, parameters}}` | flat: `{type, name, description, parameters, strict}` |
| Tool call id | `tool_calls[].id` | `output[].call_id` |
| Tool result | `{role:"tool", tool_call_id, content}` | `{type:"function_call_output", call_id, output}` |
| Structured output | `response_format.json_schema.{name,strict,schema}` | `text.format.{type,name,strict,schema}` |
| Content part types | `text`, `image_url` | `input_text`, `input_image`, `input_file` |
| Streaming | opaque `choices[0].delta` chunks | named events (`response.output_text.delta`, …) |
| Server-side history | none | `store`, `previous_response_id` |

---

## 4. Sampling parameters (both APIs)

Turn these one at a time — request **1.2** exists to be edited.

| Param | Range | What it does | What to try |
|---|---|---|---|
| `temperature` | 0–2 | Randomness. 0 ≈ deterministic, 1 = default, >1.2 degrades | Send twice at `0`, then twice at `2` |
| `top_p` | 0–1 | Nucleus sampling — only tokens in the top P probability mass are considered | Change this **or** temperature, never both |
| `max_completion_tokens` / `max_output_tokens` | int | Hard cap on the reply | Set to `10` — note `finish_reason: "length"`. It **truncates**, it does not make the model be concise |
| `stop` | ≤4 strings | Generation halts before any of them; the stop string is not returned | `["\n\n"]` to cut at the first blank line |
| `n` | int | N independent completions (Chat Completions only). Bills for all N | `3` at temperature 1 to see the spread |
| `presence_penalty` | -2–2 | Positive pushes toward new topics | |
| `frequency_penalty` | -2–2 | Positive punishes repeating the same token | `2` produces visibly strained prose |
| `seed` | int | Best-effort reproducibility at fixed temperature; compare `system_fingerprint` | |
| `logprobs` / `top_logprobs` | bool / ≤20 | Per-token probabilities and runners-up | Request 1.9 — the clearest way to *see* what temperature does |
| `user` | string | Your end-user id, for abuse tracing | |

**Reasoning models (o-series, GPT-5 family) reject `temperature` and `top_p`.**
Use `reasoning.effort` instead — see §7.

To make output shorter, say so in the prompt. `max_*_tokens` is a safety
ceiling, not a length control.

---

## 5. Structured output

Forces the reply to satisfy a JSON schema. Requests **1.5** and **2.4** both use
the `BotDecision` shape from
[`bot/promptstuffing/BotDecision.java`](../chat-service/src/main/java/com/chatapp/chatservice/bot/promptstuffing/BotDecision.java).

Three strict-mode rules — break any one and you get a 400:

1. `additionalProperties: false` on **every** object.
2. **Every** property listed in `required`. Optionality is expressed as a
   nullable type (`"type": ["string", "null"]`), never by omitting the key.
3. `strict: true`.

Rule 2 is why `ClinicTool`'s optional arguments are nullable strings rather
than absent ones.

The result still arrives as a **string** you parse yourself — in
`choices[0].message.content` or `output[].content[].text`.

> **Worth internalising:** the schema constrains *shape*, never *truth*. Ask
> request 2.4 to book you for last Tuesday and it will hand back a perfectly
> valid object saying it did. That is precisely why `BookingService` re-checks
> every booking against live data before writing — the model requests, Java
> decides (CLAUDE.md 3.9).

---

## 6. Tool calling

The collection ships all five of your real clinic tools
([`ClinicTool.java`](../chat-service/src/main/java/com/chatapp/chatservice/bot/toolcalling/ClinicTool.java)),
so a Postman round trip is the same conversation your bot has.

**The loop** (`OpenAiToolCallingBrain`, capped at `MAX_ROUNDS = 5`):

```
send prompt + tools
  → response contains function calls?
      → execute them yourself, send outputs back chained to that response
      → repeat
  → no function calls? that response carries the reply
```

Round 1 is request **1.6** / **2.5**; round 2 is **1.7** / **2.6**. The
difference between them is the whole argument for the Responses API: Chat
Completions makes you resend the full history *including the assistant's own
`tool_calls` message* (omit it and you get a 400 — a tool result answering
nothing), while the chained version sends only the `function_call_output`.

**`tool_choice`:**

| Value | Effect |
|---|---|
| `"auto"` | Default — model decides |
| `"none"` | Tools visible but unusable; forces a text reply |
| `"required"` | Must call something |
| `{"type":"function","name":"get_available_slots"}` | Force one specific tool (Responses form; Chat Completions nests it under `function`) |

`parallel_tool_calls: false` gives one call per round — much easier to follow
while learning.

**Two things to try that map to real bugs in this repo:**

- Ask *"what appointments does Vaidehi have?"*. `get_my_appointments` takes no
  patient argument, so the question cannot be expressed. That is the
  cross-patient leak (CLAUDE.md 3.9) made structurally unreachable rather than
  prompted against.
- Delete `instructions` from request **2.6** and ask for markdown. It complies —
  reproducing the live bug from CLAUDE.md 3.10 where the reply-writing round
  ran with no rules at all.

---

## 7. Reasoning models

Request **2.8**.

- `reasoning.effort`: `"minimal"` | `"low"` | `"medium"` | `"high"`.
- `reasoning.summary`: `"auto"` adds a readable `type: "reasoning"` item to
  `output[]`.

The bill is in `usage.output_tokens_details.reasoning_tokens` — thinking you pay
for and never see. Run the same prompt at `low` and `high` and compare; that gap
is the actual cost of a reasoning model.

The prompt in 2.8 is deliberately the availability subtraction Version 1 got
wrong. Worth running: it shows the reasoning tier is *better* at set arithmetic,
and still not a substitute for `get_available_slots` computing it exactly.

---

## 8. Streaming

| | Chat Completions (1.4) | Responses (2.7) |
|---|---|---|
| Enable | `stream: true` | `stream: true` |
| Chunk shape | `choices[0].delta.content` fragments | named events |
| Text event | — | `response.output_text.delta` |
| Usage | **only** if `stream_options.include_usage: true` | always, in `response.completed` |
| Terminator | literal `data: [DONE]` (not JSON) | `response.completed` |

Postman renders the raw SSE stream, which is the point — you can read exactly
what your `BotStreamListener` consumes.

The Responses events worth knowing: `response.created` (id assigned),
`response.output_text.delta` (the only one needed to push a chunk over your
WebSocket), `response.function_call_arguments.delta` (tool args arriving
piecewise), `response.completed` (the **whole** assembled response, usage
included).

That last one is the design in `OpenAiToolCallingBrain.send()`: it streams for
latency but returns a complete `Response`, so token accounting and the tool loop
are entirely unaffected by streaming.

---

## 9. Responses-only parameters

| Param | What it does |
|---|---|
| `store` | Default `true`. Keeps the response server-side so it can be chained to or retrieved. Set `false` and request 2.3 has nothing to chain to |
| `previous_response_id` | The chain pointer. A stale one → 404 with `error.param == "previous_response_id"` — exactly what `ExpiredConversationException` detects, and `DoctorAssistantBotService` retries once without it |
| `metadata` | Up to 16 key/value pairs echoed back. Handy for tagging experiments |
| `truncation` | `"auto"` drops middle items instead of erroring when a long chain exceeds the context window; `"disabled"` (default) errors |
| `include` | Request extra output, e.g. `["reasoning.encrypted_content"]` |

Requests **2.9–2.11** (retrieve / list input items / delete) let you read the
chain from the outside. Running **2.10** after a tool round shows the fully
reconstructed conversation — the API-side equivalent of your `bot_prompt_log`
table.

Deleting a response (2.11) and then re-running 2.3 against it is the cheapest
way to see the exact 404 body your expiry handling keys on.

---

## 10. Cost, and the thing this repo measured

`usage` is on every non-streaming response. The comparison CLAUDE.md 3.10
records, on the same clinic and the same conversations:

| | V1 prompt-stuffing | V2 tool-calling |
|---|---|---|
| avg system prompt | 14,915 chars | 3,341 chars |
| avg input tokens/turn | 5,536 | 2,768 |

You can reproduce the shape of that here: request **1.3** (manual history) grows
its input every turn you append; request **2.3** does not. Add a paragraph of
fake clinic data to 1.3's system message and watch `prompt_tokens` jump on
*every* subsequent call — that is V1's floor rising with each doctor added.

---

## 11. Errors you will hit

| Status | Meaning | Usual cause |
|---|---|---|
| 400 `invalid_request_error` | Malformed body | A strict-mode rule broken (§5), or a tool result with no matching assistant message |
| 401 | Bad key | Placeholder still in `api_key`, or pasted into Initial value instead of Current value |
| 404 on `/responses` | Unknown id | Response aged out or was deleted. Check `error.param` |
| 429 | Rate/quota | Backoff, or no credit on the key |
| `finish_reason: "length"` | Truncated | `max_*_tokens` too low — the reply was cut, not shortened |
| 400 on a reasoning model | Unsupported param | `temperature`/`top_p` sent to an o-series/GPT-5 model |
