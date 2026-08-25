package com.chatapp.chatservice.bot.toolcalling;

/**
 * One tool call and what it returned, kept for the trace written to
 * {@code bot_prompt_log.tool_calls}.
 *
 * <p>Version 1 is auditable because its whole prompt is one string in a column.
 * Version 2's prompt is small and says almost nothing — what it actually looked
 * up is the interesting part, and without this it would be invisible. The
 * cross-patient leak took an hour to find precisely because the prompt could not
 * be read; that lesson applies here too, in a different shape.
 *
 * @param name      the tool the model called
 * @param arguments the raw JSON arguments it sent, unparsed and unedited
 * @param result    the JSON handed back, including any {@code error} field
 */
public record ToolInvocation(String name, String arguments, String result) {
}
