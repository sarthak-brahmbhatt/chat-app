package com.chatapp.chatservice.bot.toolcalling;

import com.chatapp.chatservice.bot.clinic.AvailabilityService;
import com.chatapp.chatservice.bot.clinic.BookingOutcome;
import com.chatapp.chatservice.bot.clinic.BookingRequest;
import com.chatapp.chatservice.bot.clinic.BookingService;
import com.chatapp.chatservice.bot.clinic.FreeSlot;
import com.chatapp.chatservice.bot.clinic.entity.AppointmentStatus;
import com.chatapp.chatservice.bot.clinic.entity.Doctor;
import com.chatapp.chatservice.bot.clinic.entity.DoctorAvailability;
import com.chatapp.chatservice.bot.clinic.repository.AppointmentRepository;
import com.chatapp.chatservice.bot.clinic.repository.DoctorAvailabilityRepository;
import com.chatapp.chatservice.bot.clinic.repository.DoctorRepository;
import com.chatapp.chatservice.bot.clinic.entity.AvailabilityStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runs one tool call against the real clinic (CLAUDE.md 3.10).
 *
 * <p>The trust boundary for Version 2, and the direct counterpart of Version 1's
 * prompt data. Everything arriving here is a name and a blob of JSON produced by
 * a language model, so every argument is treated as untrusted input: parsed,
 * range-checked, and refused with an explanation the model can act on rather
 * than an exception.
 *
 * <p><b>The caller's identity is a constructor-time fact, not an argument.</b>
 * {@code currentUserId} comes from the authenticated WebSocket session and is
 * the only user id any of these methods will use. That is what makes Version 1's
 * cross-patient leak structurally impossible here rather than merely fixed: the
 * model has no way to name a different patient, because no tool accepts one.
 *
 * <p>Every result is JSON, because that is what goes back over the wire as a
 * {@code function_call_output}. Errors are returned as JSON too — an
 * {@code {"error": "..."}} the model can read and recover from beats an
 * exception that kills the turn.
 */
public class ClinicToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ClinicToolExecutor.class);

    private final DoctorRepository doctorRepository;
    private final DoctorAvailabilityRepository availabilityRepository;
    private final AppointmentRepository appointmentRepository;
    private final AvailabilityService availabilityService;
    private final BookingService bookingService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int horizonDays;
    private final long currentUserId;

    public ClinicToolExecutor(
            DoctorRepository doctorRepository,
            DoctorAvailabilityRepository availabilityRepository,
            AppointmentRepository appointmentRepository,
            AvailabilityService availabilityService,
            BookingService bookingService,
            ObjectMapper objectMapper,
            Clock clock,
            int horizonDays,
            long currentUserId) {
        this.doctorRepository = doctorRepository;
        this.availabilityRepository = availabilityRepository;
        this.appointmentRepository = appointmentRepository;
        this.availabilityService = availabilityService;
        this.bookingService = bookingService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.horizonDays = horizonDays;
        this.currentUserId = currentUserId;
    }

    /**
     * @param toolName  what the model called it
     * @param argsJson  the raw arguments string from the tool call
     * @return JSON to hand back as this call's output — never null, never a
     *         thrown exception. A tool that fails still has to say something,
     *         or the conversation stalls with the model waiting on a result
     *         that never comes.
     */
    @Transactional
    public String execute(String toolName, String argsJson) {
        try {
            JsonNode args = argsJson == null || argsJson.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(argsJson);

            return ClinicTool.byName(toolName)
                    .map(tool -> switch (tool) {
                        case LIST_SPECIALTIES -> listSpecialties();
                        case FIND_DOCTORS -> findDoctors(text(args, "specialty"));
                        case GET_AVAILABLE_SLOTS -> getAvailableSlots(
                                text(args, "date"), text(args, "specialty"), number(args, "doctor_id"));
                        case GET_MY_APPOINTMENTS -> getMyAppointments();
                        case BOOK_APPOINTMENT -> bookAppointment(
                                number(args, "availability_id"), text(args, "booked_for_date"));
                    })
                    .orElseGet(() -> error("No such tool: " + toolName));
        } catch (Exception e) {
            // Deliberately broad. A tool blowing up must not take the turn with
            // it — the model gets told, and can apologise or try something else.
            log.warn("Tool {} failed with args {}: {}", toolName, argsJson, e.getMessage(), e);
            return error("That lookup failed. Tell the patient something went wrong and offer to try again.");
        }
    }

    private String listSpecialties() {
        return json(Map.of("specialties", doctorRepository.findActiveSpecialties()));
    }

    private String findDoctors(String specialty) {
        Map<Long, String> workingDays = availabilityRepository
                .findActivePattern(AvailabilityStatus.AVAILABLE).stream()
                .collect(Collectors.groupingBy(
                        DoctorAvailability::getDoctorId,
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(
                                Collectors.mapping(a -> a.getDayOfWeek().name(),
                                        Collectors.toCollection(java.util.LinkedHashSet::new)),
                                days -> String.join(", ", days))));

        List<Map<String, Object>> doctors = doctorRepository.findByActiveTrueOrderByNameAsc().stream()
                .filter(d -> specialty == null || d.getSpecialty().equalsIgnoreCase(specialty))
                .map(d -> Map.<String, Object>of(
                        "doctor_id", d.getId(),
                        "name", d.getName(),
                        "specialty", d.getSpecialty(),
                        "works_on", workingDays.getOrDefault(d.getId(), "(no hours configured)")))
                .toList();

        if (doctors.isEmpty() && specialty != null) {
            // Said explicitly rather than returned as a bare empty list, because
            // "no doctors" and "no such specialty" lead to different replies and
            // an empty array does not distinguish them.
            return json(Map.of(
                    "doctors", List.of(),
                    "note", "This clinic has no doctors in '" + specialty
                            + "'. Tell the patient plainly and do not suggest a different specialty."));
        }
        return json(Map.of("doctors", doctors));
    }

    private String getAvailableSlots(String rawDate, String specialty, Long doctorId) {
        LocalDate date;
        try {
            date = LocalDate.parse(rawDate);
        } catch (DateTimeParseException | NullPointerException e) {
            return error("date must be a real calendar date as YYYY-MM-DD. You sent: " + rawDate);
        }

        LocalDate today = LocalDate.now(clock);
        if (date.isBefore(today)) {
            return error("That date (" + date + ") is in the past. Today is " + today + ".");
        }
        LocalDate horizonEnd = today.plusDays(horizonDays - 1L);
        if (date.isAfter(horizonEnd)) {
            return error("Bookings are only open up to " + horizonEnd
                    + ". Tell the patient you cannot book beyond that date yet.");
        }

        // The whole point of Version 2. This is already the subtraction —
        // doctor active, slot not blocked, weekday matching, nothing booked —
        // computed in SQL by the one place that owns it. The model receives an
        // answer, not the raw material for one.
        List<Map<String, Object>> slots = availabilityService.freeSlotsOn(date).stream()
                .filter(s -> specialty == null || s.specialty().equalsIgnoreCase(specialty))
                .filter(s -> doctorId == null || s.doctorId().equals(doctorId))
                .map(ClinicToolExecutor::slotJson)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("weekday", date.getDayOfWeek().name());
        result.put("available_slots", slots);
        if (slots.isEmpty()) {
            result.put("note", "Nothing is free that day for that doctor or specialty. "
                    + "Offer another date, or another doctor of the same specialty.");
        }
        return json(result);
    }

    private static Map<String, Object> slotJson(FreeSlot s) {
        return Map.of(
                "availability_id", s.availabilityId(),
                "doctor_id", s.doctorId(),
                "doctor", s.doctorName(),
                "specialty", s.specialty(),
                "start", s.startTime().toString(),
                "end", s.endTime().toString());
    }

    private String getMyAppointments() {
        LocalDate today = LocalDate.now(clock);
        Map<Long, Doctor> byId = doctorRepository.findAll().stream()
                .collect(Collectors.toMap(Doctor::getId, d -> d));

        // Scoped to currentUserId, which came from the session. No argument
        // could widen this.
        List<Map<String, Object>> mine = appointmentRepository
                .findInWindowForUser(currentUserId, AppointmentStatus.BOOKED, today, today.plusYears(1))
                .stream()
                .map(ap -> Map.<String, Object>of(
                        "date", ap.getBookedForDate().toString(),
                        "doctor", byId.containsKey(ap.getDoctorId())
                                ? byId.get(ap.getDoctorId()).getName() : "(unknown)",
                        "start", ap.getStartTime().toString(),
                        "end", ap.getEndTime().toString()))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appointments", mine);
        if (mine.isEmpty()) {
            result.put("note", "This patient has no appointments booked. Tell them so directly.");
        }
        return json(result);
    }

    private String bookAppointment(Long availabilityId, String bookedForDate) {
        // Straight through BookingService — the same validation, the same unique
        // constraint, the same refusals as Version 1. The model reaches the
        // database through exactly one door, and it is a door that argues back.
        BookingOutcome outcome = bookingService.book(
                new BookingRequest(availabilityId, bookedForDate, "booked"), currentUserId);

        if (!outcome.booked()) {
            return json(Map.of(
                    "booked", false,
                    "reason", "That slot is no longer available. Apologise and offer another time."));
        }
        return json(Map.of("booked", true, "appointment_id", outcome.appointmentId()));
    }

    private static String text(JsonNode args, String field) {
        JsonNode n = args.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static Long number(JsonNode args, String field) {
        JsonNode n = args.get(field);
        return n == null || n.isNull() || !n.canConvertToLong() ? null : n.asLong();
    }

    private String error(String message) {
        return json(Map.of("error", message));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("Could not serialise tool result", e);
            return "{\"error\":\"internal serialisation failure\"}";
        }
    }

    /** For the trace written to {@code bot_prompt_log.tool_calls}. */
    public static List<Map<String, String>> traceOf(List<ToolInvocation> invocations) {
        List<Map<String, String>> trace = new ArrayList<>();
        for (ToolInvocation i : invocations) {
            trace.add(Map.of("tool", i.name(), "arguments", i.arguments(), "result", i.result()));
        }
        return trace;
    }
}
