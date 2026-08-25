package com.chatapp.chatservice.bot.toolcalling;

import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The five things the model may ask the clinic to do (CLAUDE.md 3.10).
 *
 * <p>This is Version 2's replacement for Version 1's stuffed prompt. Instead of
 * being handed every doctor, every working-hours row and every booking up front
 * and asked to reason over them, the model is handed these five verbs and looks
 * up only what the conversation actually needs.
 *
 * <p>Two problems disappear structurally rather than being prompted around:
 *
 * <ul>
 *   <li><b>The availability arithmetic.</b> Version 1 asked the model to
 *       subtract bookings from a schedule and it got it wrong — offering a slot
 *       that was in its own booked list. {@link #GET_AVAILABLE_SLOTS} returns
 *       the answer already computed by {@code AvailabilityService}. There is no
 *       arithmetic left to get wrong.
 *   <li><b>The cross-patient leak.</b> Version 1 handed over every patient's
 *       bookings in one unattributed list and the model misattributed them.
 *       {@link #GET_MY_APPOINTMENTS} takes <b>no patient argument at all</b> —
 *       the executor scopes it to the authenticated session. The model cannot
 *       ask about someone else because the question cannot be expressed.
 * </ul>
 *
 * <p>Every schema is {@code strict: true}, which requires that every property is
 * listed in {@code required} and that {@code additionalProperties} is false. An
 * optional argument is therefore expressed as a NULLABLE type rather than an
 * absent one — see {@link #nullableString}.
 */
public enum ClinicTool {

    /**
     * What the clinic offers. The model is told to call this before claiming a
     * specialty is unavailable — Version 1 needed the whole list injected up
     * front to stop it inventing a dermatologist; here it can just ask.
     */
    LIST_SPECIALTIES(
            "list_specialties",
            "List every medical specialty this clinic offers. Call this before telling a patient "
                    + "the clinic does or does not have a given specialty. The list is exhaustive.",
            Map.of()),

    /**
     * Doctors, optionally filtered by specialty. Returns each doctor's working
     * weekdays alongside them, because "which days does she work?" is the very
     * next question and a second round trip for it is wasted latency.
     */
    FIND_DOCTORS(
            "find_doctors",
            "List the clinic's doctors and the weekdays each one works. Optionally filter to a "
                    + "single specialty. Use this to offer a patient their choice of doctor.",
            Map.of("specialty", nullableString(
                    "Exact specialty to filter by, as returned by list_specialties. "
                            + "Null returns every doctor."))),

    /**
     * The one that matters. Returns slots that are genuinely bookable — the
     * doctor is active, the slot is not blocked, the weekday matches, and
     * nothing is booked against it on that date.
     */
    GET_AVAILABLE_SLOTS(
            "get_available_slots",
            "Find appointment slots that are genuinely free on a specific date. Returns only "
                    + "bookable slots — anything already taken, blocked, or belonging to a doctor "
                    + "who is not working is already excluded. Never guess availability; call this.",
            new LinkedHashMap<>(Map.of(
                    "date", Map.of("type", "string",
                            "description", "The date to check, as YYYY-MM-DD."),
                    "specialty", nullableString("Only slots for doctors in this specialty. Null for all."),
                    "doctor_id", Map.of("type", new String[]{"integer", "null"},
                            "description", "Only slots for this doctor. Null for all.")))),

    /**
     * The caller's own appointments. Takes no arguments on purpose — see the
     * class comment.
     */
    GET_MY_APPOINTMENTS(
            "get_my_appointments",
            "List the appointments belonging to the patient you are currently talking to. "
                    + "This is the ONLY way to learn about a patient's bookings, and it returns "
                    + "only theirs. Never describe any other appointment as belonging to them.",
            Map.of()),

    /**
     * The only write. Still goes through {@code BookingService}, so every
     * validation Version 1 had applies unchanged — the model can request a
     * booking, it cannot make one.
     */
    BOOK_APPOINTMENT(
            "book_appointment",
            "Book one specific slot for the patient you are talking to. Only call this after the "
                    + "patient has explicitly confirmed a slot you offered them. The clinic "
                    + "re-checks the slot before writing, so this can still refuse.",
            new LinkedHashMap<>(Map.of(
                    "availability_id", Map.of("type", "integer",
                            "description", "The availability_id of the slot, exactly as returned "
                                    + "by get_available_slots."),
                    "booked_for_date", Map.of("type", "string",
                            "description", "The date to book, as YYYY-MM-DD."))));

    private final String toolName;
    private final String description;
    private final Map<String, Object> properties;

    ClinicTool(String toolName, String description, Map<String, Object> properties) {
        this.toolName = toolName;
        this.description = description;
        this.properties = properties;
    }

    public String toolName() {
        return toolName;
    }

    /** Looks up a tool by the name the model used, or empty if it invented one. */
    public static java.util.Optional<ClinicTool> byName(String name) {
        for (ClinicTool tool : values()) {
            if (tool.toolName.equals(name)) {
                return java.util.Optional.of(tool);
            }
        }
        return java.util.Optional.empty();
    }

    /** All five, as the SDK's tool type, ready to attach to a request. */
    public static List<FunctionTool> allAsFunctionTools() {
        return java.util.Arrays.stream(values()).map(ClinicTool::asFunctionTool).toList();
    }

    private FunctionTool asFunctionTool() {
        return FunctionTool.builder()
                .name(toolName)
                .description(description)
                .strict(true)
                .parameters(FunctionTool.Parameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from(properties))
                        // strict mode requires EVERY property to be listed here.
                        // Optionality is expressed by the type being nullable,
                        // not by the property being absent.
                        .putAdditionalProperty("required", JsonValue.from(List.copyOf(properties.keySet())))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    private static Map<String, Object> nullableString(String description) {
        return Map.of("type", new String[]{"string", "null"}, "description", description);
    }
}
