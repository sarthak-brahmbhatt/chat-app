package com.chatapp.chatservice.bot;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.Optional;

/**
 * The shape the model is FORCED to reply in (CLAUDE.md 3.9 §6.3).
 *
 * <p>The division of labour this type encodes is the core safety property of the
 * whole feature: <b>the model decides, Java acts.</b> A model turn cannot write
 * to the database. The most it can do is return {@code action = BOOK} and name a
 * slot — a request, which {@link BookingService} then re-validates against live
 * data and is free to refuse. No tools, no function calls, no write path of any
 * kind reaches the model in Version 1.
 *
 * <p>The OpenAI SDK derives a strict JSON schema from this record's shape and
 * annotations, and the API constrains decoding to it — so a reply that omits a
 * field or invents one is not something to handle, it is something that cannot
 * be produced. The generated schema marks all four properties required, sets
 * {@code additionalProperties: false} and {@code strict: true}, and renders the
 * two {@link Optional} fields as {@code ["integer","null"]} /
 * {@code ["string","null"]}. Every {@code @JsonPropertyDescription} below is
 * copied verbatim into that schema and is read by the model — they are prompt
 * text that happens to live in annotations, not documentation for humans, which
 * is why they are phrased as instructions.
 *
 * <p>A record rather than a class with setters: the SDK constructs this through
 * Jackson's record support, and immutability means a decision cannot be quietly
 * edited between being parsed and being validated.
 */
@JsonClassDescription("""
        The assistant's response for one turn: what to say to the user, and \
        whether that reply is accompanied by a booking request.""")
public record BotDecision(

        @JsonProperty("reply_to_user")
        @JsonPropertyDescription("""
                The natural-language message to show the user. Always required. Write it as \
                the final text the user reads — no preamble, no JSON, no restating these \
                instructions. When action is BOOK, write it as a confirmation of the booking.""")
        String replyToUser,

        @JsonProperty("action")
        @JsonPropertyDescription("""
                BOOK only when the user has explicitly confirmed a specific doctor, date and \
                time that you have already offered them. NONE for everything else, including \
                greetings, questions, offering options, and asking for confirmation.""")
        BotAction action,

        @JsonProperty("availability_id")
        @JsonPropertyDescription("""
                The availability_id of the slot to book, copied exactly from the availability \
                data provided to you. Required when action is BOOK; null otherwise. Never \
                invent an id that was not in the provided data.""")
        Optional<Long> availabilityId,

        @JsonProperty("booked_for_date")
        @JsonPropertyDescription("""
                The calendar date to book, as YYYY-MM-DD. Required when action is BOOK; null \
                otherwise. Must be a real upcoming date whose weekday matches the day_of_week \
                of the chosen availability_id.""")
        Optional<String> bookedForDate) {

    /**
     * Whether this turn is asking for a booking at all.
     *
     * <p>Checks {@code action} only, and deliberately not "are the two optional
     * fields populated". Presence of an id is not consent — the model can mention
     * a slot while still asking the user to confirm it, and treating that as a
     * booking is precisely the "never book on ambiguous input" failure §6.2
     * guards against. {@link BookingService} validates the fields separately.
     */
    public boolean isBookingRequest() {
        return action == BotAction.BOOK;
    }
}
